package com.richarddewan.barcodescanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.camera.core.*

import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetector
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetectorOptions
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import kotlinx.android.synthetic.main.activity_main.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), LifecycleOwner {

    companion object {
        const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val RC_PICK_FILE = 1
    }

    private val PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    private lateinit var firebaseVisionImage: FirebaseVisionImage
    private lateinit var firebaseVisionBarcodeDetector: FirebaseVisionBarcodeDetector
    private lateinit var firebaseVisionBarcodeDetectorOptions: FirebaseVisionBarcodeDetectorOptions
    private lateinit var metadata: FirebaseVisionImageMetadata
    private lateinit var viewFinder: TextureView
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //initialize viewFinder
        viewFinder = view_finder

        if (allPermissionsGranted()) {
            viewFinder.post { startCamera() }

        } else {
            ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        //set detector options
        firebaseVisionBarcodeDetectorOptions = FirebaseVisionBarcodeDetectorOptions.Builder()
            .setBarcodeFormats(
                FirebaseVisionBarcode.FORMAT_QR_CODE,
                FirebaseVisionBarcode.FORMAT_EAN_13,
                FirebaseVisionBarcode.FORMAT_CODE_93,
                FirebaseVisionBarcode.FORMAT_CODE_39,
                FirebaseVisionBarcode.FORMAT_CODE_128,
                FirebaseVisionBarcode.FORMAT_AZTEC
            )
            .build()


        //get detector and specify the formats to recognize:
        firebaseVisionBarcodeDetector = FirebaseVision.getInstance()
            .getVisionBarcodeDetector(firebaseVisionBarcodeDetectorOptions)

        btnImportImage.setOnClickListener {
            Intent(Intent.ACTION_GET_CONTENT).run {
                type = "image/*"
                putExtra(Intent.EXTRA_LOCAL_ONLY,true)
                startActivityForResult(this, RC_PICK_FILE)
            }
        }


        sw_image.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked){
                viewFinder.visibility = View.INVISIBLE
                imageView.visibility = View.VISIBLE
            }
            else {
                viewFinder.visibility = View.VISIBLE
                imageView.visibility = View.INVISIBLE
            }
        }

    }

    private fun startDetector(image: FirebaseVisionImage) {
        firebaseVisionBarcodeDetector.detectInImage(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val bounds = barcode.boundingBox
                    val corners = barcode.cornerPoints
                    val rawValue = barcode.rawValue
                    val format =  barcode.format
                    val valueType = barcode.valueType
                    // See API reference for complete list of supported types
                    when (valueType) {
                        FirebaseVisionBarcode.TYPE_WIFI -> {
                            val ssid = barcode.wifi!!.ssid
                            val password = barcode.wifi!!.password
                            val type = barcode.wifi!!.encryptionType
                        }
                        FirebaseVisionBarcode.TYPE_URL -> {
                            val title = barcode.url!!.title
                            val url = barcode.url!!.url
                        }
                        else -> {
                            txtBarcodeValue.setText(rawValue.toString())

                        }
                    }
                }
            }
            .addOnFailureListener { error ->
                Log.e(TAG, error.message.toString())

            }

    }

    private fun startCamera() {
        try {
            // Create configuration object for the viewfinder use case
            val previewConfig = PreviewConfig.Builder().apply {
                setTargetResolution(Size(640, 480))
                setTargetRotation(viewFinder.display.rotation)
            }.build()

            // Build the viewfinder use case
            val preview = Preview(previewConfig)

            // Every time the viewfinder is updated, recompute layout
            preview.setOnPreviewOutputUpdateListener {

                // To update the SurfaceTexture, we have to remove it and re-add it
                val parent = viewFinder.parent as ViewGroup
                parent.removeView(viewFinder)
                parent.addView(viewFinder, 0)

                viewFinder.surfaceTexture = it.surfaceTexture
                updateTransform()
            }

            // Create configuration object for the image capture use case
            val imageCaptureConfig = ImageCaptureConfig.Builder().apply {
                // We don't set a resolution for image capture; instead, we
                // select a capture mode which will infer the appropriate
                // resolution based on aspect ration and requested mode
                setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)
            }.build()

            // Build the image capture use case and attach button click listener
            val imageCapture = ImageCapture(imageCaptureConfig)

            btnCaptureImage.setOnClickListener {
                val file = File(externalMediaDirs.first(), "${System.currentTimeMillis()}.jpg")

                imageCapture.takePicture(
                    file,
                    executor,
                    object : ImageCapture.OnImageSavedListener {
                        @RequiresApi(Build.VERSION_CODES.O)
                        override fun onImageSaved(file: File) {

                            val msg = "Photo capture succeeded: ${file.absolutePath}"
                            Log.d("CameraXApp", msg)
                            viewFinder.post {
                                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                            }

                            firebaseVisionImage =
                                FirebaseVisionImage.fromFilePath(
                                    baseContext,
                                    Uri.fromFile(file)
                                )

                            startDetector(firebaseVisionImage)
                        }

                        override fun onError(
                            imageCaptureError: ImageCapture.ImageCaptureError,
                            message: String,
                            cause: Throwable?
                        ) {
                            val msg = "Photo capture failed: $message"
                            Log.e("CameraXApp", msg, cause)
                            viewFinder.post {
                                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                            }
                        }

                    })

            }

            // Bind use cases to lifecycle
            // If Android Studio complains about "this" being not a LifecycleOwner
            // try rebuilding the project or updating the appcompat dependency to
            // version 1.1.0 or higher.
            CameraX.bindToLifecycle(this, preview, imageCapture)

        } catch (e: Exception) {

        }
    }

    private fun updateTransform() {
        try {
            val matrix = Matrix()
            // Compute the center of the view finder
            val centerX = viewFinder.width / 2f
            val centerY = viewFinder.height / 2f

            // Correct preview output to account for display rotation
            val rotationDegrees = when (viewFinder.display.rotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> return
            }

            matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

            // Finally, apply transformations to our TextureView
            viewFinder.setTransform(matrix)

        } catch (e: Exception) {

        }
    }

    /**
     * Check if all permission specified in the manifest have been granted
     */
    private fun allPermissionsGranted() = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Process result from permission request dialog box, has the request
     * been granted? If yes, start Camera. Otherwise display a toast
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                viewFinder.post { startCamera() }
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_PICK_FILE){
            if (resultCode == Activity.RESULT_OK){
                view_finder.visibility = View.INVISIBLE
                imageView.visibility = View.VISIBLE
                imageView.setImageURI(data?.data)
                sw_image.isChecked = true

                firebaseVisionImage =
                    FirebaseVisionImage.fromFilePath(
                        baseContext,
                        data?.data!!
                    )

                startDetector(firebaseVisionImage)
            }
        }
    }
}
