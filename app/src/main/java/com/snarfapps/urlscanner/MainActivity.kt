package com.snarfapps.urlscanner

import android.content.Intent
import android.content.pm.PackageManager
import android.media.Image
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


/** Helper type alias used for analysis use case callbacks */
typealias LumaListener = (luma: ImageProxy?) -> Unit
class MainActivity : AppCompatActivity() {
    companion object{
        private val CAMERA_PERMISSIONS = arrayOf(android.Manifest.permission.CAMERA )
        private val PERMISSION_REQUEST_CODE = 999
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val TAG = "URL-Scanner"
    }

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    var cameraPreview: PreviewView? = null
    var urlButton: Button?=null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cameraPreview = findViewById(R.id.pvCameraPreview)

        urlButton = findViewById(R.id.scannedURL)

        urlButton!!.setOnClickListener(object : View.OnClickListener {
            override fun onClick(view: View?) {
                Log.e(TAG, "CLICK!")
                if(Patterns.WEB_URL.matcher(urlButton!!.text.toString()).matches()){
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(urlButton!!.text.toString()))
                    startActivity(browserIntent)
                }
            }

        })

        if(checkAllPermissions()){
            startCamera()
        }else{
            ActivityCompat.requestPermissions(this,CAMERA_PERMISSIONS,PERMISSION_REQUEST_CODE)
        }


        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    fun checkAllPermissions() = CAMERA_PERMISSIONS.all {
        return ContextCompat.checkSelfPermission(baseContext,it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if(requestCode == PERMISSION_REQUEST_CODE){
            if(checkAllPermissions()){
                startCamera()
            }
            else{
                Toast.makeText(this,"Camera Permissions are not granted.",Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    fun startCamera(){
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(cameraPreview?.createSurfaceProvider())
                    }

            val imageAnalyzer = ImageAnalysis.Builder()
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, URLScannAnalyzer { luma ->



                            val mediaImage: Image? = luma?.image
                            val image = InputImage.fromMediaImage(mediaImage!!, luma!!.getImageInfo().getRotationDegrees())


                            val recognizer: TextRecognizer = TextRecognition.getClient()

                            val result = recognizer.process(image!!)
                                    .addOnSuccessListener { visionText ->
                                        // Task completed successfully
                                        // ...

                                        for(block in visionText.textBlocks){

                                            if(Patterns.WEB_URL.matcher(block.text).matches()){
                                                Log.e(TAG,"Found url: ${block.text}")
                                                urlButton!!.text = block.text
                                            }
                                        }

                                        luma.close()
                                    }
                                    .addOnFailureListener { e ->
                                        // Task failed with an exception
                                        // ...

                                        Log.e(TAG,"Failed: ${e.message}")
                                        throw e
                                    }
                                    .addOnCompleteListener { c ->

                                    }
                        })
                    }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview)
                cameraProvider.bindToLifecycle(this,cameraSelector,imageAnalyzer)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private class URLScannAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {


        private val frameRateWindow = 8
        private val frameTimestamps = ArrayList<Long>(5)
        private var lastAnalyzedTimestamp = 0L
        var framesPerSecond: Double = -1.0


        private val listeners = ArrayList<LumaListener>().apply { listener?.let { add(it) } }

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        override fun analyze(imageProxy: ImageProxy) {

            // If there are no listeners attached, we don't need to perform analysis
            if (listeners.isEmpty()) {
                imageProxy.close()
                return
            }



            // Keep track of frames analyzed
            val currentTime = System.currentTimeMillis()
            frameTimestamps.add(currentTime)

            // Compute the FPS using a moving average
            while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeAt(frameTimestamps.lastIndex)
            val timestampFirst = frameTimestamps.first() ?: currentTime
            val timestampLast = frameTimestamps.last() ?: currentTime
            framesPerSecond = 1.0 / ((timestampFirst - timestampLast) /
                    frameTimestamps.size.coerceAtLeast(1).toDouble()) * 1000.0

            // Analysis could take an arbitrarily long amount of time
            // Since we are running in a different thread, it won't stall other use cases

            lastAnalyzedTimestamp = frameTimestamps.first()



            val buffer = imageProxy.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()




            listener(imageProxy)

        }

    }
}