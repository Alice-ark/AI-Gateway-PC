' AI Gateway PC 桌面入口：wscript 无控制台，隐藏拉起 launcher.ps1（探活/拉起/开管理台）
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh  = CreateObject("WScript.Shell")
root = fso.GetParentFolderName(WScript.ScriptFullName)
sh.Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & root & "\launcher.ps1""", 0, False
