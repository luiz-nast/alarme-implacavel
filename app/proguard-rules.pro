# O ML Kit encontra partes de si mesmo por reflexão: os registradores de componentes declarados no
# manifesto e o criador do detector de rosto embutido (ThickFaceDetectorCreator). No modo completo do
# R8 (padrão do AGP 9), "-keep class X" sem membros não mantém o construtor. Sem estas regras,
# FaceDetection.getClient quebrava com NullPointerException ao abrir a câmera. Manter essas classes
# inteiras custa pouco perto dos ~8 MB do modelo nativo.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
