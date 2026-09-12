param(
    # Gradle task names / flags, e.g. .\run-gradle.ps1 clean test build -PuseDml=true
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

# Gradle writes the build log in the console's OEM code page, which on Windows PowerShell 5.1 is
# typically CP850/CP1252 while Out-File defaults to UTF-16LE. The mismatch makes the log look like
# garbage in editors. Set both the file encoding and the console code page to UTF-8 so the log is
# readable regardless of who produced it.
$log = 'gradle-run.log'
$utf8 = [System.Text.UTF8Encoding]::new($false)

# `*>` / `>` in Windows PowerShell 5.1 always write UTF-16LE regardless of $OutputEncoding, so pipe
# through Out-File instead. Feeding the whole pipeline to Out-File still leaves Gradle's exit code in
# $LASTEXITCODE, because the native command is what sets it.
& .\gradlew.bat --no-daemon --console=plain @GradleArgs 2>&1 | Out-File -FilePath $log -Encoding utf8
$exitCode = $LASTEXITCODE

"EXIT=$exitCode" | Out-File -FilePath $log -Append -Encoding utf8

# Echo only the interesting lines so the caller does not have to page through the whole log.
Get-Content $log -Encoding utf8 |
    Where-Object { $_ -match '^(Build variant:|> Task :|BUILD |FAILURE|EXIT=|.*: error:|.*: warning:)' } |
    ForEach-Object { $_ }

Write-Output "LOG=$log EXIT=$exitCode"
exit $exitCode