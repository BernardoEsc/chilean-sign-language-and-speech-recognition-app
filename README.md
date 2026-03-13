# **Chilean Sign Language and Speech Recognition App**

Aplicación Android que reconoce gestos de la Lengua de Señas Chilena (LSCh) y transcribe voz a texto en español.

Los modelos trabajan en local. Por lo tanto, no se necesita estar conectado a internet para usar la aplicación. 

.

La App fue desarrollada como parte de nuestro Trabajo de Título:

**"Aplicación móvil de apoyo comunicacional para personas con discapacidad auditiva mediante reconocimiento de voz y señas"**.



## Transcripción Voz a Texto

Para transcribir voz a texto en español se utiliza Vosk.

Vosk es un kit de herramientas de reconocimiento de voz de código abierto y sin conexión.

- Se integró el ejemplo de android [vosk-android-demo](https://github.com/alphacep/vosk-android-demo)
- El modelo de vosk utilizado es el `vosk-model-small-es-0.42`

Descarga el modelo desde su pagina web [Vosk Models](https://alphacephei.com/vosk/models)  


## Reconocimiento de Señas

### MediaPipe Solutions

Para reconocer señas primero, se extraen puntos de referencia con MediaPipe Solutions.

Las tareas de MediaPipe utilizadas son:
- Face Landmarker
- Hand Landmarker
- Pose Landmarker

Se integraron los ejemplos de android de [mediapipe-samples](https://github.com/google-ai-edge/mediapipe-samples)

### TensorFlow Lite

Luego, se entrenó un [modelo](app/src/main/assets/modelo.tflite) con TensorFlow Lite capaz de reconocer **32 gestos** de la **Lengua de Señas Chilena**:
- las 27 letras del _**abecedario en LSCh**_
- 5 palabras: **_"Estudiar", "Mañana", "Prueba", "Tú", "Yo"_**.

| <div align="center"> Los 32 gestos de la LSCh que reconoce el modelo </div> |
|:-------|
| ![Señas](https://github.com/user-attachments/assets/55027ac9-68d1-4b72-a1ac-0d86c79d14c4) |

Fuentes Imágenes:
- [Abecedario en LSCh](https://media.biobiochile.cl/wp-content/uploads/2024/09/lenguas-de-senas02-614x768.jpg)
- https://www.youtube.com/watch?v=NGtdIUSh2SE
- Diccionario Bilingüe Lengua de Señas Chilena-Español



## Autores
- Bernardo Escalona
- Gustavo Solís
