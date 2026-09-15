@ECHO OFF
SET DIR=%~dp0
SET CLASSPATH=%DIR%gradle\wrapper\gradle-wrapper.jar
IF NOT EXIST "%CLASSPATH%" (
  ECHO Missing gradle-wrapper.jar. Generate it with a local Gradle installation: gradle wrapper
  EXIT /B 1
)
java -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
