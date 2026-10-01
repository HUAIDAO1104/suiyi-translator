# 随译 · SuiYi

个人旅行交流用的 Android AI 翻译应用，支持中文、英语、泰语。

## 手机上使用

1. 在本仓库 [Releases](https://github.com/HUAIDAO1104/suiyi-translator/releases/latest) 下载 `suiyi.apk` 并安装。需要 Android 8.0 或更新版本。
2. 打开“设置”，填写公司提供的 HTTPS API 地址（通常以 `/v1` 结尾）及自己的 API 密钥。地址示例：`https://your-gateway.example/v1`。
3. 点击“读取模型列表”，选择有权限使用的文字模型；也可手动填写名称。保存设置。读取列表不发送翻译请求。
4. 选择交流双方语言，点“说中文”“说ไทย”等按钮，说完后自动翻译。另一方使用另一个语音按钮。也可输入文字并点“翻译文字”。
5. 译文支持朗读、复制、收藏和大字展示。“开启新对话”会清除当前模型上下文；本机记录单独保留。
6. 后续在“设置”点击“检查更新”，下载后由 Android 显示安装确认。首次更新可能需要允许随译安装应用；无需卸载旧版。

手机听写由系统语音服务提供。不同手机的中英泰听写支持不同；需要对应服务和网络时，请在手机系统设置中配置。系统朗读优先选择已安装的本地语音包，没有泰语语音包时仍可阅读、复制和展示译文。

“音频直传（实验）”录制最长 30 秒的 16 kHz 单声道 WAV，使用 OpenAI 兼容 `chat/completions` 的 `input_audio` 内容请求识别和翻译。必须同时满足模型支持音频、网关接受该音频格式；支持图片的模型不一定支持语音，原生 Gemini/Anthropic 接口也不能直接套用此格式。接口不支持时请切回手机听写，应用不会自动再调用其他模型。

## 费用与数据

无需服务器、域名、数据库或应用商店账号。翻译调用你自己的 API，计费由模型平台决定；音频请求及上下文也可能计费。手机网络流量仍使用自己的套餐。公开仓库使用标准 GitHub Actions runner；本项目没有付费服务或付费 runner。

API 密钥和可选 GitHub 读取令牌使用 Android Keystore 的 AES-GCM 加密保存在手机，禁用云备份和设备迁移。公司 API 地址和密钥不内置在源码中。原文、译文及最近最多 6 次对话会发往你配置的接口；音频直传也发送录音。手机听写的数据处理由系统语音提供商决定。历史及收藏保存在应用私有存储中，可关闭历史保存；没有分析统计或广告。

语言模型仍可能误译。短句、清晰发音及核对价格、数字和否定词有助于实际交流。首版不包含离线 AI 翻译、自动语言判断、后台持续监听、实时同声传译或拍照翻译。

## GitHub 构建和手机更新

`Android build and release` 工作流在 `main` 代码更新或手动 `Run workflow` 后执行单元测试、Android Lint、签名构建，并发布 `suiyi.apk` 和 `update.json`。版本号为 `0.1.<run_number>`，Android versionCode 使用递增的工作流 run_number。

固定签名需在仓库 **Settings → Secrets and variables → Actions** 添加两个 Secret：

- `ANDROID_KEYSTORE_BASE64`：个人固定签名 PKCS12 文件的 Base64 文本。
- `ANDROID_KEYSTORE_PASSWORD`：该文件和别名 `suiyi` 的密码。

签名文件及密码须在仓库外安全备份。不要加入 Git，也不要作为普通 Actions artifact 上传。丢失签名后无法覆盖更新旧安装；重新签名只能卸载重装。更换或重命名工作流可能重置 run_number，必须确保后续 versionCode 大于已发布版本。

首次浏览器上传使用 `bootstrap.zip`，首个工作流会展开为可浏览的源码并删除归档。后续直接编辑源码即可。工作流需要 `contents: write` 来提交展开后的源码和创建 Release；不接受外部 PR 触发签名发布。

应用默认从构建时注入的当前仓库检查更新。下载会验证 SHA-256、文件大小、包名、版本和安装包签名。公开仓库不需要 GitHub 令牌；私有仓库可以在手机填写仅有该仓库 Contents 读取权限的令牌。自动检查每天最多一次，也可关闭。

## 本地开发

使用 JDK 17、Android SDK 35；Gradle Wrapper 8.11.1（含官方分发 SHA-256 校验）和 Android Gradle Plugin 8.9.2。设置本机 `ANDROID_HOME` 或忽略的 `local.properties`。

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

本地 debug APK 使用临时调试签名，只用于开发；手机应安装 Release 中的正式 APK，以便接收相同签名的后续更新。正式构建环境变量参见工作流。

协议测试涵盖 HTTPS 地址限制、仓库路径验证、源文本与指令隔离、上下文条数限制、音频请求及结果格式、响应截断、密钥隐藏、更新信息和 WAV 文件头。真实网关及各手机的听写、泰语朗读和系统安装器仍需实机验证。
