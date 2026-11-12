# 从源码构建

本工程使用 Windows PowerShell、JDK 25、Python 3.13 和 Android SDK/NDK 的独立构建脚本，没有 Gradle wrapper。发布的 APK 是 `0.3.13-trial`、`versionCode 17`、`arm64-v8a`，最低 Android 8.0/API 26。

0.3.13 更新为已完成本轮手机实测的 v6 模型及版本号，保留原遮罩和推理/硬件逻辑；已知显示空档和未知枪械误识别见 VALIDATION.md。公开构建脚本改为使用本机工具路径与开发者自己的签名证书；这不意味着自行构建的 APK 与发布包逐字节相同。

## 工具与依赖

先安装 JDK 25 与 Python 3.13，将 `JAVA_HOME` 指向 JDK 根目录；也可在后续命令中显式传入 `-JdkRoot`。Python 的 `py` 启动器应位于 PATH。随后在仓库根目录运行：

```powershell
.\prepare-tools.ps1
.\prepare-ndi.ps1
.\prepare-onnx.ps1
```

`prepare-tools.ps1` 下载固定的 Android API 35 和 build tools 35；`prepare-ndi.ps1` 下载固定的 NDK r30、NDI Android SDK 6.3.2；`prepare-onnx.ps1` 下载 ONNX Runtime Android 1.23.2。脚本核对下载散列，散列不符时停止。NDI 的下载地址未带具体小版本号，供应商替换内容后可能需要重新核对版本与许可，不能直接跳过散列检查。

Android 工具默认放在仓库上一级的 `android-tools/`。如使用已有安装，对前两个准备脚本和 `build.ps1` 传入相同的 `-ToolsRoot`；目录结构需符合脚本要求。项目代码的 AGPL、NDI 集成的有限附加许可及 NDI/ORT 第三方条款分别见 `LICENSE`、`LICENSE-NDI-EXCEPTION.txt`、`THIRD-PARTY.md` 和 `app/src/main/assets/licenses/`。

## 创建自己的签名证书

发布者的签名私钥和口令不公开。以下操作只在你选择创建新证书时执行；已存在同名文件会停止，构建脚本也不会生成或替换证书。

```powershell
if (-not $env:JAVA_HOME) { throw '请先设置 JAVA_HOME' }
$localKey = Join-Path $PWD 'keys\yingshu-local.jks'
if (Test-Path -LiteralPath $localKey) { throw '证书已存在，请保留并使用原证书' }
New-Item -ItemType Directory -Path (Split-Path $localKey -Parent) -Force | Out-Null
& (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -storetype JKS `
  -keystore $localKey -alias yingshu-local -keyalg RSA -keysize 3072 `
  -validity 3650 -dname 'CN=Yingshu Local Development'
if ($LASTEXITCODE -ne 0) { throw '创建证书失败' }
```

按 `keytool` 提示输入自己的口令，并妥善备份证书。`keys/` 和常见证书文件后缀已被 Git 忽略。可在提示中为私钥选择与证书库相同的口令；若选择不同口令，构建时分别输入。

## 构建 APK

构建只通过环境变量名称把口令传给 `apksigner`，不把口令写入源码或命令参数。以下命令读取口令、构建，最后清除本进程中的两个口令变量：

```powershell
$storeSecret = Read-Host '证书库口令' -AsSecureString
$keySecret = Read-Host '私钥口令（若相同，请再次输入证书库口令）' -AsSecureString
try {
  $env:YINGSHU_KEYSTORE_PASSWORD = [Net.NetworkCredential]::new('', $storeSecret).Password
  $env:YINGSHU_KEY_PASSWORD = [Net.NetworkCredential]::new('', $keySecret).Password
  .\build.ps1
} finally {
  Remove-Item Env:YINGSHU_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
  Remove-Item Env:YINGSHU_KEY_PASSWORD -ErrorAction SilentlyContinue
  $storeSecret.Dispose()
  $keySecret.Dispose()
}
```

常用参数：

| 参数 | 默认值/作用 |
| --- | --- |
| `-JdkRoot` | `JAVA_HOME`，JDK 根目录 |
| `-ToolsRoot` | 仓库上一级的 `android-tools` |
| `-PythonExe` | `py` |
| `-SigningKeysRoot` | 仓库内的 `keys` |
| `-KeystoreName` | `yingshu-local.jks` |
| `-KeyAlias` | `yingshu-local` |
| `-StorePasswordEnvironment` | `YINGSHU_KEYSTORE_PASSWORD` |
| `-KeyPasswordEnvironment` | `YINGSHU_KEY_PASSWORD` |

产物位于 `build/yingshu-hardware-0.3.13-trial.apk` 和 `build/build-info.json`。脚本会核对模型契约、编译 Java/JNI、打包、签名并验证签名和 ZIP 对齐，不会自动安装或卸载手机应用。

自行签名的 APK 不能作为更新覆盖另一个证书签名的安装包。若需要更换安装，先在应用中导出按键配置；卸载会删除应用数据。保留自己的签名证书才能继续为自己的安装版本提供更新。

## 本地检查

以下自包含检查仅需要 JDK，不访问网络、不连接手机，也不发送 M3 按键：

```powershell
.\tests\run-local-checks.ps1
# 或 .\tests\run-local-checks.ps1 -JdkRoot '你的 JDK 根目录'
py .\native\verify_joint_assets.py .\app\src\main\assets\models
```

Java 检查覆盖 M3 编码、UVC 描述符/组帧及确定性异常输入、图像预处理、联合输出校验、规则选择/确认、按键搜索和文本导入导出。其中 `RuleTextCodecTest` 包含 5,329 项检查。

这些检查验证代码逻辑和资产完整性，不代表模型在独立录屏上的准确率、手机推理速率、OBS 端到端延迟或接收端实际按键效果。

## 可选与历史测试

- `verify_model_preprocessing.py` 需要 `numpy`、`opencv-python`、`Pillow`；接受 `--jdk` 或 `JAVA_HOME`，可通过 `--mask` 指定当前遮罩。
- `verify_optic_detector_selection.py` 需要对应的外部 fixture manifest 和二进制数据；这些历史夹具不在当前仓库内。
- `verify_locator_selection.py` 需要 `--locator-source` 指向含匹配历史 `optic_locator.py` 的目录，并安装 `numpy`；它不属于当前联合模型的默认验收。
- `DeviceModelSmoke` 和 `JointModelBenchmark` 属于旧 schema，入口已禁用。它们不能替代当前 schema 6 的真机验收，也不应直接针对当前模型解除禁用运行。

历史测试必须配合其对应源码、数据和模型契约使用。发布包的真机验证范围见版本交付说明。
