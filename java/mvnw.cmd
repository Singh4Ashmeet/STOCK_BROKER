@rem
@rem Licensed to the Apache Software Foundation (ASF) under one
@rem or more contributor license agreements.  See the NOTICE file
@rem distributed with this work for additional information
@rem regarding copyright ownership.  The ASF licenses this file
@rem to you under the Apache License, Version 2.0 (the
@rem "License"); you may not use this file except in compliance
@rem with the License.  You may obtain a copy of the License at
@rem
@rem     http://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing,
@rem software distributed under the License is distributed on an
@rem "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@rem KIND, either express or implied.  See the License for the
@rem specific language governing permissions and limitations
@rem under the License.

@echo off

@rem Maven Start Up Batch script
@rem

@rem Add default JVM options here. You can also use JAVA_OPTS and MAVEN_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS=-Xmx1024m -Dfile.encoding=UTF-8

set APP_NAME=Maven
set APP_BASE_NAME=%~n0

@rem Use the maximum available, or set MAX_FD != -1 to use that value.
set MAX_FD=maximum

@rem OS specific support.
if "%OS%"=="Windows_NT" set cygwin=false
if "%OS%"=="Windows_NT" set msys=false
if "%OS%"=="Windows_NT" set mingw=false
if "%OS%"=="Windows_NT" set darwin=false

set CLASSWORLD_LAUNCHER=org.codehaus.plexus.classworlds.launcher.Launcher

@rem Attempt to set APP_HOME
set "APP_HOME=%~dp0"
if "%APP_HOME:~-1%"=="\" set "APP_HOME=%APP_HOME:~0,-1%"
cd /d "%APP_HOME%"

@rem For Cygwin, switch paths to Windows format before running java
if "%cygwin%"=="true" (
    if not "%APP_HOME%"=="" set "APP_HOME=%APP_HOME:\=/%"
    if not "%JAVA_HOME%"=="" set "JAVA_HOME=%JAVA_HOME:\=/%"
    if not "%CLASSPATH%"=="" set "CLASSPATH=%CLASSPATH:\=/%"
)

@rem Determine the Java command to use to start the JVM.
set "JAVACMD="
if exist "%JAVA_HOME%\bin\java.exe" set "JAVACMD=%JAVA_HOME%\bin\java.exe"
if exist "%JAVA_HOME%bin\java.exe" set "JAVACMD=%JAVA_HOME%bin\java.exe"
if exist "%JAVA_HOME%\jre\bin\java.exe" set "JAVACMD=%JAVA_HOME%\jre\bin\java.exe"
if exist "%JAVA_HOME%jre\bin\java.exe" set "JAVACMD=%JAVA_HOME%jre\bin\java.exe"

if not defined JAVACMD if not "%JAVA_HOME%"=="" (
    echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
    echo.
    echo Please set the JAVA_HOME variable in your environment to match the
    echo location of your Java installation.
    goto error
)

if not defined JAVACMD (
    set "JAVACMD=java"
    where java >nul 2>nul
    if errorlevel 1 (
        echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
        echo.
        echo Please set the JAVA_HOME variable in your environment to match the
        echo location of your Java installation.
        goto error
    )
)

@rem Execute Maven
"%JAVACMD%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %MAVEN_OPTS% "-Dmaven.multiModuleProjectDirectory=%APP_HOME%" -cp "%APP_HOME%\.mvn\wrapper\maven-wrapper.jar" org.apache.maven.wrapper.MavenWrapperMain %*

@rem End of script
goto end

:error
exit /b 1

:end