$os = Get-CimInstance Win32_OperatingSystem
$totalGB = [math]::Round($os.TotalVisibleMemorySize/1MB, 1)
$freeGB = [math]::Round($os.FreePhysicalMemory/1MB, 1)
Write-Output ("TotalGB: " + $totalGB + "  FreeGB: " + $freeGB)
Get-Process | Where-Object { $_.Name -match 'java|studio' } | ForEach-Object {
  Write-Output ($_.Name + " pid=" + $_.Id + " memMB=" + [math]::Round($_.WorkingSet64/1MB))
}
