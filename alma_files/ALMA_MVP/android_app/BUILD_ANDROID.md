# ALMA Android — preparación de compilación (Bloque 458)

## Requisitos locales
- JDK 17
- Android SDK con API 35
- Gradle 8.9 o Android Studio compatible

## Primera preparación del wrapper
Este paquete no inventa ni incluye un `gradle-wrapper.jar` binario no verificado.
En una PC/entorno con Gradle instalado:
```bash
cd android_app
gradle wrapper --gradle-version 8.9
```

## Compilar debug
```bash
./gradlew assembleDebug
```
Salida esperada:
`app/build/outputs/apk/debug/app-debug.apk`

## Compilar release
```bash
./gradlew assembleRelease
```

## Seguridad
- Debug permite HTTP local para `10.0.2.2`.
- Release bloquea tráfico HTTP sin cifrar.
- Producción debe usar una URL HTTPS accesible.
- No colocar `OPENAI_API_KEY` ni secretos del proveedor en Android.
