$ErrorActionPreference = 'Continue'
# env (from local-dev-env.ps1)
$env:JWT_SECRET = 'lVUmsO2nXX96SNrO5tvQvg0WJZPfJppP4VirrEOpYJZp5p58hjvhdrCtgzzr91v2Nz3tX2OAXW+pEmlT036s+KQ=='
$env:DB_PASSWORD = 'aa64221886'
$env:RUNTIME_CALLBACK_TOKEN = '7270fa52a00ae6df12dc41d02d881a215daac8b5fe2e23ce97b89669fcdd4548'
$env:DINGTALK_ENABLED = 'true'
$env:DINGTALK_APP_KEY = 'dinggnhx3yz9jhvks1yz'
$env:DINGTALK_APP_SECRET = 'Hg4rllh9dJAAXfkqLr3-QYNaOQl-8bUzna0XoXT3wjkDDHEBUUPmmVhGOdVl3dYI'
$env:DINGTALK_AGENT_ID = '4787175947'
chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8'
$env:MAVEN_OPTS = '-Dfile.encoding=UTF-8'
# correct paths for this machine
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location 'E:\workspace\agent-platform\backend'
& 'C:\Program Files\apache-maven-3.9.16\bin\mvn.cmd' spring-boot:run
