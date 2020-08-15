package com.snarfapps.urlscanner

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.Image
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.webkit.URLUtil
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LiveData
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import java.io.File
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.ArrayList


/** Helper type alias used for analysis use case callbacks */
typealias ImageAnalysisListener = (imageProxy: ImageProxy?,timeProcessed: Double) -> Unit
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
    var tvAnalysisTime: TextView?=null
    var rectangleView: View? = null
    var ivFlash : ImageButton? = null
    var flashOn = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cameraPreview = findViewById(R.id.pvCameraPreview)

        tvAnalysisTime = findViewById(R.id.tvAnalysisTime)


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

        rectangleView = findViewById(R.id.rectangleView)

        ivFlash = findViewById(R.id.ivFlash)

        ivFlash!!.setOnClickListener {
            cameraControl?.enableTorch(!flashOn)
            flashOn=!flashOn

        }

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

    fun updateScannerView( proxy:ImageProxy?){

        runOnUiThread {
        }



    }
    var cameraControl : CameraControl? = null
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
                        it.setAnalyzer(cameraExecutor, URLScannAnalyzer { imageProxy: ImageProxy?, analysisTime: Double ->
                            updateScannerView(imageProxy)

                            var rect = Rect(500, 3000,300,100)

                            imageProxy!!.setCropRect(rect)

                            //TODO continue with the crop part!


                            val mediaImage: Image? = imageProxy?.image
                            val image = InputImage.fromMediaImage(mediaImage!!, imageProxy!!.getImageInfo().getRotationDegrees())


                            val recognizer: TextRecognizer = TextRecognition.getClient()

                            val result = recognizer.process(image!!)
                                    .addOnSuccessListener { visionText ->

                                        for(block in visionText.textBlocks){

                                            if( URLUtil.isValidUrl(block.text) &&  Patterns.WEB_URL.matcher(block.text).matches()){
                                                Log.e(TAG,"Found url: ${block.text}")
                                                urlButton!!.text = block.text
                                                tvAnalysisTime!!.text = "%.${2}f".format(analysisTime, Locale.ENGLISH)+" s"
                                                break
                                            }
                                        }

                                        imageProxy.close()
                                    }
                                    .addOnFailureListener { e ->
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
               val camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview,imageAnalyzer)



                cameraControl = camera.cameraControl

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private class URLScannAnalyzer(private val listener: ImageAnalysisListener) : ImageAnalysis.Analyzer {


        private val frameRateWindow = 8
        private val frameTimestamps = ArrayList<Long>(5)
        private var lastAnalyzedTimestamp = 0L
        var framesPerSecond: Double = -1.0


        private val listeners = ArrayList<ImageAnalysisListener>().apply { listener?.let { add(it) } }

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


            //so we get fps, now we calculate how much time it would take for a frame to be processed
            val secondsOfEachFrame = 1.0 / framesPerSecond

            // Analysis could take an arbitrarily long amount of time
            // Since we are running in a different thread, it won't stall other use cases

            lastAnalyzedTimestamp = frameTimestamps.first()



            Log.e(TAG,"$framesPerSecond")
            listener(imageProxy,secondsOfEachFrame * -1)

        }

    }
}

private fun ByteBuffer.toByteArray(): ByteArray? {
    rewind()    // Rewind the buffer to zero
    val data = ByteArray(remaining())
    get(data)   // Copy the buffer into a byte array
    return data // Return the byte array
}

