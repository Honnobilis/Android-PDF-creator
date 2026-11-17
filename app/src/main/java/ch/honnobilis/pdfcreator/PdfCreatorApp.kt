package ch.honnobilis.pdfcreator

import android.app.Application
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.opencv.android.OpenCVLoader

class PdfCreatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        if (!OpenCVLoader.initLocal()) Log.e("PdfCreator", "OpenCV failed to load")
    }
}
