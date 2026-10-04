package com.implacavel.alarme

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
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

/** Detector de rosto falhando esse tanto de quadros seguidos (uns 3 s) é defeito. */
private const val FALHAS_SEGUIDAS_MAX = 30

/**
 * Câmera frontal com detecção de rosto (ML Kit, roda no aparelho, sem internet). A cada quadro
 * informa uma [Leitura]. Com defeito (não abre, erro grave da câmera ou o detector falhando em
 * todo quadro), informa [Leitura.SEM_CAMERA].
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
        var camera: Camera? = null
        var falhasSeguidas = 0 // só mexida na thread principal, onde o ML Kit entrega o resultado
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
                analise.setAnalyzer(executor) { quadro ->
                    analisar(
                        quadro, detector,
                        onLeitura = {
                            falhasSeguidas = 0
                            informar(it)
                        },
                        onFalha = {
                            if (++falhasSeguidas == FALHAS_SEGUIDAS_MAX) {
                                Log.w(TAG, "Câmera: o detector de rosto falhou $FALHAS_SEGUIDAS_MAX vezes seguidas")
                                informar(Leitura.SEM_CAMERA)
                            }
                        },
                    )
                }
                val cameras = futuro.get()
                cameras.unbindAll()
                val aberta = cameras.bindToLifecycle(dono, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analise)
                aberta.cameraInfo.cameraState.observe(dono) { estado -> estado.error?.let { tratarErro(it, informar) } }
                camera = aberta
                Log.i(TAG, "Câmera: aberta")
            }.onFailure {
                Log.w(TAG, "Câmera: não abriu", it)
                informar(Leitura.SEM_CAMERA)
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            descartado = true
            camera?.cameraInfo?.cameraState?.removeObservers(dono)
            if (futuro.isDone) runCatching { futuro.get().unbindAll() }
            detector.close()
            executor.shutdown()
        }
    }
    AndroidView(factory = { visor }, modifier = modifier)
}

/**
 * Erro grave da câmera (não tem como voltar a funcionar) é defeito: [onLeitura] recebe SEM_CAMERA.
 * Câmera bloqueada no atalho de privacidade do Android é escolha, não defeito: liberando, ela volta.
 * Os erros passageiros (outro app usando a câmera) o CameraX resolve sozinho.
 */
private fun tratarErro(erro: CameraState.StateError, onLeitura: (Leitura) -> Unit) {
    Log.w(TAG, "Câmera: erro ${erro.code} (${erro.type})")
    if (erro.type == CameraState.ErrorType.CRITICAL && erro.code != CameraState.ERROR_CAMERA_DISABLED) onLeitura(Leitura.SEM_CAMERA)
}

@OptIn(ExperimentalGetImage::class)
private fun analisar(quadro: ImageProxy, detector: FaceDetector, onLeitura: (Leitura) -> Unit, onFalha: () -> Unit) {
    val imagem = quadro.image
    if (imagem == null) {
        quadro.close()
        return
    }
    detector.process(InputImage.fromMediaImage(imagem, quadro.imageInfo.rotationDegrees))
        .addOnSuccessListener { rostos -> onLeitura(lerRosto(rostos)) }
        .addOnFailureListener { onFalha() }
        .addOnCompleteListener { quadro.close() }
}

/** Considera só o maior rosto (o mais perto da câmera). */
private fun lerRosto(rostos: List<Face>): Leitura {
    val rosto = rostos.maxByOrNull { it.boundingBox.width() } ?: return Leitura.SEM_ROSTO
    return classificarRosto(rosto.headEulerAngleY, rosto.headEulerAngleX, rosto.leftEyeOpenProbability, rosto.rightEyeOpenProbability)
}

/**
 * Modo preciso e rosto a partir de 10% da largura da imagem: no modo rápido, e com o mínimo de 20%,
 * o rosto sumia a cada 1 ou 2 s com o celular a um braço de distância e o anel não enchia.
 */
private val OPCOES_ROSTO = FaceDetectorOptions.Builder()
    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // liga a probabilidade de olho aberto
    .setMinFaceSize(0.1f)
    .build()
