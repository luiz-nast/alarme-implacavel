package com.implacavel.alarme

import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

/**
 * Câmera frontal com detecção de rosto (ML Kit, roda no aparelho, sem internet). A cada quadro
 * informa uma [Leitura]; se a câmera não abrir, informa [Leitura.SEM_CAMERA].
 */
@Composable
fun CameraOlhos(modifier: Modifier, onLeitura: (Leitura) -> Unit) {
    val ctx = LocalContext.current
    val dono = LocalLifecycleOwner.current
    val informar by rememberUpdatedState(onLeitura)
    val visor = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE // permite recortar em círculo
        }
    }
    DisposableEffect(dono) {
        var descartado = false
        val executor = Executors.newSingleThreadExecutor()
        val detector = FaceDetection.getClient(OPCOES_ROSTO)
        val futuro = ProcessCameraProvider.getInstance(ctx)
        futuro.addListener({
            if (descartado) return@addListener
            runCatching {
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(visor.surfaceProvider) }
                val analise = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analise.setAnalyzer(executor) { quadro -> analisar(quadro, detector) { informar(it) } }
                val cameras = futuro.get()
                cameras.unbindAll()
                cameras.bindToLifecycle(dono, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analise)
            }.onFailure { informar(Leitura.SEM_CAMERA) }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            descartado = true
            if (futuro.isDone) runCatching { futuro.get().unbindAll() }
            detector.close()
            executor.shutdown()
        }
    }
    AndroidView(factory = { visor }, modifier = modifier)
}

@OptIn(ExperimentalGetImage::class)
private fun analisar(quadro: ImageProxy, detector: FaceDetector, onLeitura: (Leitura) -> Unit) {
    val imagem = quadro.image
    if (imagem == null) {
        quadro.close()
        return
    }
    detector.process(InputImage.fromMediaImage(imagem, quadro.imageInfo.rotationDegrees))
        .addOnSuccessListener { rostos -> onLeitura(lerRosto(rostos)) }
        .addOnCompleteListener { quadro.close() }
}

/** Considera só o maior rosto (o mais perto da câmera). */
private fun lerRosto(rostos: List<Face>): Leitura {
    val rosto = rostos.maxByOrNull { it.boundingBox.width() } ?: return Leitura.SEM_ROSTO
    return classificarRosto(rosto.headEulerAngleY, rosto.headEulerAngleX, rosto.leftEyeOpenProbability, rosto.rightEyeOpenProbability)
}

private val OPCOES_ROSTO = FaceDetectorOptions.Builder()
    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // liga a probabilidade de olho aberto
    .setMinFaceSize(0.2f)
    .build()
