@echo off
REM
REM  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
REM
REM  Copyright (c) 2021 Payara Foundation and/or its affiliates. All rights reserved.
REM
REM  The contents of this file are subject to the terms of either the GNU
REM  General Public License Version 2 only ("GPL") or the Common Development
REM  and Distribution License("CDDL") (collectively, the "License").  You
REM  may not use this file except in compliance with the License.  You can
REM  obtain a copy of the License at
REM  https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
REM  or packager/legal/LICENSE.txt.  See the License for the specific
REM  language governing permissions and limitations under the License.
REM
REM  When distributing the software, include this License Header Notice in each
REM  file and include the License file at packager/legal/LICENSE.txt.
REM
REM  GPL Classpath Exception:
REM  Oracle designates this particular file as subject to the "Classpath"
REM  exception as provided by Oracle in the GPL Version 2 section of the License
REM  file that accompanied this code.
REM
REM  Modifications:
REM  If applicable, add the following below the License Header, with the fields
REM  enclosed by brackets [] replaced by your own identifying information:
REM  "Portions Copyright [year] [name of copyright owner]"
REM
REM  Contributor(s):
REM  If you wish your version of this file to be governed by only the CDDL or
REM  only the GPL Version 2, indicate your decision by adding "[Contributor]
REM  elects to include this software in this distribution under the [CDDL or GPL
REM  Version 2] license."  If you don't indicate a single choice of license, a
REM  recipient has the option to distribute your version of this file under
REM  either the CDDL, the GPL Version 2 or to extend the choice of license to
REM  its licensees as provided above.  However, if you add GPL Version 2 code
REM  and therefore, elected the GPL Version 2 license, then the option applies
REM  only if the new code is made subject to such option by the copyright
REM  holder.
REM

VERIFY OTHER 2>nul
setlocal ENABLEEXTENSIONS
if ERRORLEVEL 0 goto sourceProperties
echo "Unable to enable extensions"
exit /B 1

:sourceProperties
if exist %~dp0..\config\upgrade-tool.bat (
    call "%~dp0..\config\upgrade-tool.bat"
    goto checkCurrentPresent
) else (
    echo %~dp0..\config\upgrade-tool.bat not present! This is unexpected: Exiting since this implies you haven't yet run the upgrade-server command or have cleared it
    exit /B 1
)

:checkCurrentPresent
if exist %~dp0..\modules (
    goto checkStagedPresent
) else (
    echo No current install present! This is unexpected: Exiting since this implies you're running this script from a staged or old install
    exit /B 1
)

:checkStagedPresent
if exist %~dp0..\modules.new (
    echo Staged install present! This is unexpected: Exiting since this script would overwrite the staged install
    exit /B 1
) else (
    goto checkOldPresent
)

:checkOldPresent
if exist %~dp0..\modules.old (
    goto moveFiles
) else (
    echo No old install present! This is unexpected: Exiting since there's nothing to roll back
    exit /B 1
)

:moveFiles
for %%a in ("%PAYARA_UPGRADE_DIRS:,=" "%") do (
    echo Moving %%a to new
    move %~dp0..\%%a %~dp0..\%%a.new
    if ERRORLEVEL 1 (
        if %%a=="mq" (
            echo Ignoring error moving MQ directory to staged location, assuming you're rolling back a payara-web distribution
        ) else (
            if %%a=="..\mq" (
                echo Ignoring error moving MQ directory to staged location, assuming you're rolling back a payara-web distribution
            ) else (
                set WARN=true
            )
        )
    )

    echo Moving old %%a to expected location
    move %~dp0..\%%a.old %~dp0..\%%a
    if ERRORLEVEL 1 (
        if %%a=="mq" (
            echo Ignoring error moving old MQ directory to expected location, assuming you're rolling back to a payara-web distribution
        ) else (
            if %%a=="..\mq" (
                echo Ignoring error moving old MQ directory to expected location, assuming you're rolling back to a payara-web distribution
            ) else (
                set WARN=true
            )
        )
    )
)

if "%WARN%"=="true" (
    echo A command didn't complete successfully! Check the logs and your current install. Skipping reinstallation of nodes, please run the reinstall-nodes ASadmin command if this is incorrect.
    exit /B 1
) else (
    call %~dp0..\bin\asadmin.bat reinstall-nodes %*
    if ERRORLEVEL 1 (
        set WARN=true
    )
)

if "%WARN%"=="true" (
    echo A command didn't complete successfully! Check the logs and your current install. Please use the restore-domain ASadmin command to restore your desired domains if everything appears fine.
    exit /B 1
) else (
    echo Please use the restore-domain ASadmin command to restore your desired domains.
)


