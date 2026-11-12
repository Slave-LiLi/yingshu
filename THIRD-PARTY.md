# 第三方组件与模型

项目自有代码采用 GNU AGPL 第 3 版。第三方代码、模型与素材保留各自许可证，不因本项目开源而自动改变。

| 项目 | 当前使用 | 许可或声明 |
| --- | --- | --- |
| ONNX Runtime Android | 1.23.2，本地 CPU 推理 | [MIT 及第三方声明](app/src/main/assets/licenses/onnxruntime/) |
| NDI Android SDK | 6.3.2，局域网视频接收 | [NDI 声明](NOTICE-NDI.txt)、[SDK 协议](app/src/main/assets/licenses/NDI-SDK-License.txt)、[第三方声明](app/src/main/assets/licenses/NDI-third-party.txt) |
| Ultralytics 模型与训练工具 | ONNX 元数据标注 Ultralytics 8.4.203、AGPL-3.0 | [AGPL-3.0 全文](app/src/main/assets/licenses/Ultralytics-AGPL-3.0.txt)、[官方许可说明](https://www.ultralytics.com/license) |

## NDI 集成附加许可

NDI SDK 属于专有组件。项目仅对自行编写且有权许可的 Yingshu 应用/NDI 集成部分提供 [有限链接附加许可](LICENSE-NDI-EXCEPTION.txt)，允许与供应商的未修改 NDI Android 6.3.2 运行库组合及分发，同时保留项目自有部分的 AGPL 源码义务及 NDI 的独立条款。该附加许可不适用于 Ultralytics 模型、训练软件、ONNX Runtime 或其他第三方材料，也不代表对任意后续组合的许可兼容性保证。

仓库不包含完整 NDI SDK、NDI Tools 或运行库缓存。准备脚本从供应商获取依赖并核对散列；APK 内的 NDI 运行库仅随应用打包。接收者必须继续遵守适用于这些第三方组件的分发条件。

## 冻结模型

模型采用其上游 AGPL-3.0 许可，未追加禁止收费限制。项目自有代码也采用 AGPL，分发与修改须遵守相应源码、许可声明及适用的网络交互义务。模型的可编辑权重、来源和训练/导出说明与发行源码一并提供；训练录像不因项目开源自动变为可再分发素材，具体包含范围以仓库中的模型说明为准。

当前冻结 ONNX 的 SHA-256 为 `0ed610790a14323061d8ba102c9c70aac32a03ecd28d872d187fd14587440528`，遮罩为 `0f035302b617d2cb0c62b728a4158d2eae9f1df6f5215dd51f7447238e9cfaac`。ONNX 导出元数据保留历史本机训练路径；它不用于运行时加载，也不代表仓库包含该路径下的私人数据。0.3.13 使用冻结的 v6 模型；训练、权重混合与验证范围见 docs/MODEL.md。许可保持 AGPL，运行契约和遮罩未变。

## 许可与品牌

官方发行免费；AGPL 允许符合条款的收费分发。中文使用说明是摘要，不取代许可证全文，不附加禁止收费的限制。游戏名称、图标、M3、NDI 及其他商标属于各自权利人。本项目不是这些产品的官方发行。
