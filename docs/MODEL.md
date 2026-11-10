# 冻结 v5 模型与继续训练

本项目发布可运行的 ONNX 模型，以及对应的可编辑 PyTorch checkpoint。模型与本目录脚本遵循 AGPL-3.0；全文见根目录 `LICENSE`，Ultralytics 上游声明见 `app/src/main/assets/licenses/Ultralytics-AGPL-3.0.txt`。

## 发布资产

| 文件 | 大小 / SHA-256 |
| --- | --- |
| `training/weights/joint-v5.pt` | 5,456,239 字节；`f0fecfc75104ee62f55421d22c31f6000a6209afe9baf875aedd44407bd9090d` |
| `app/src/main/assets/models/joint.onnx` | 9,701,857 字节；`850d46265ff4f2cdeb0e27c545752571423292ee1522798c4f282b1af75f5af5` |
| `app/src/main/assets/models/panel-mask.bin` | 40,960 字节；`0f035302b617d2cb0c62b728a4158d2eae9f1df6f5215dd51f7447238e9cfaac` |

checkpoint 与实际选中的 `candidate-best` 逐字节一致；公开整理没有修改权重、ONNX 或遮罩。序列化 checkpoint 保留原训练元数据，包括历史本机路径；路径不是运行依赖，不代表该目录中的录屏或数据也在本仓库。只加载自己信任来源的 `.pt` 文件。

这套资产是继续训练、修改和导出的入口。没有提供私人录屏、标注图片、原始数据集或更早的 9 类 checkpoint，因此**不能据此宣称可以逐字节复现历史完整训练**。训练记录与数据划分统计见 `training/provenance-v5.json`；原始训练选项见 `training/hyperparameters-v5.json`。本次发布没有重新训练模型。

## 模型与预处理契约

- 13 类顺序：`VK47, R301, R99, NEMESIS, ALTERNATOR, LSTAR, PROWLER, CAR, none, one_x, two_x, three_x, four_x`。
- 枪械类为 0–7，倍镜类为 8–12。单个模型共享计算，输出同时包含枪械与倍镜框。
- 帧基准为 1920×1080。裁剪 `(x=1472, y=936, width=320, height=128)`，按二进制遮罩将丢弃像素置为黑色，上下各填充 96 行 RGB 114，得到 320×320。
- 输入为 RGB、float32、NCHW，数值除以 255，形状 `[1,3,320,320]`。
- 输出为 `[1,300,6]`，每行为 `x1,y1,x2,y2,score,class`；ONNX opset 17，102 个 Conv 节点，导出不额外启用 NMS。
- Android 使用枪械阈值 0.25、倍镜阈值 0.8、倍镜冲突阈值 0.8 和差值 0.1。完整后处理仍以 `ModelPostprocess.java` 和 `JointOutputValidator.java` 为准。

## 环境

历史环境使用 Python 3.13.14、Ultralytics 8.4.203、PyTorch 2.9.1+cu128 和 torchvision 0.24.1+cu128。其余已观察到的版本见 `training/environment-observed.json`，主要依赖版本固定在 `training/requirements.txt`；这不是全部传递依赖的锁文件。

在单独的 Python 环境中按自己的 CPU/CUDA 环境选择官方 PyTorch wheel，再安装其余依赖。不要把 CUDA 12.8 视为所有机器的要求。脚本默认禁用 Ultralytics 自动安装和联网检查，不会为缺失依赖自动下载模型或安装包。

```powershell
py -m venv .venv-training
.\.venv-training\Scripts\python.exe -m pip install -r training\requirements.txt
```

## 准备自己的标注数据

使用自己有权使用的游戏帧。每张图标注当前枪械和倍镜两个目标；无镜也有自己的 `none` 类。不要把切枪动画、无法确认的倍镜或其他配件直接当成有把握的标签。

可先生成与模型一致的遮罩及填充图，再在 320×320 图上标注 YOLO 归一化框：

```powershell
python training\prepare_input.py --input your-frame.png --output build\sample-320.png
```

非 1920×1080 帧默认拒绝。确需沿用历史低分辨率素材的处理方式时，显式加 `--normalize-1080p`，先按 `cv2.INTER_LINEAR` 将整帧放大到 1920×1080；放大不会补充原图不存在的细节。

复制 `training/data.example.yaml`，填写自己的数据路径并保留完整类顺序。将连续录像按持枪片段/时间块划分训练、验证和留出集，并保留切枪边界间隔，避免相邻帧跨集合泄漏。历史 v5 保留旧数据划分；新组合每组按权重采样为 64 条，最终训练列表 861 条、验证 36 张。公开通用训练入口不会擅自重建或宣称复现这份私人采样列表。

## 继续训练

```powershell
python training\finetune.py --data your-data.yaml --output build\my-finetune --device 0
# CPU 可改为 --device cpu；也可以显式调整 --epochs 和 --batch。
```

默认从已训练的 13 类 checkpoint 继续，采用历史 v5 的 80 轮、AdamW、学习率 0.0003、batch 8、种子 20261109 等选项。输出目录必须尚不存在，脚本不会覆盖已有训练结果。新的数据、起点或设备都会改变训练结果，应另行验证。

`training/initialize_heads.py` 保留了历史 9 类扩展到 13 类的逻辑：旧类映射为 `[0,1,2,3,8,9,10,11,12]`，六个分类投影新增四枪行，其他权重保持不变。它需要自行提供匹配的旧 9 类 checkpoint，不能对本仓库已完成扩展的 13 类权重再次运行。

## 导出与验证

```powershell
python training\export_onnx.py --output build\my-export
# 修改后的权重：加 --checkpoint build\my-finetune\weights\best.pt
```

导出只写入新的输出目录，生成 checkpoint 副本、ONNX 和结构检查结果，不修改 Android 已验收模型资产或散列。重复导出可因时间戳和元数据变化而产生不同文件散列；相同张量结构不代表新模型具有相同识别准确率。

公开整理时实际运行了这个导出入口：重新导出的 ONNX 图结构与冻结图逐字节一致，三个确定性合成输入的输出逐元素相等；合成 1080p 帧的裁剪、遮罩和填充也与独立构造的预期数组完全一致。记录见 `training/publication-check.json`。这些检查没有使用新标注数据，不是准确率或手机速度测试。

将新模型放入应用前，需要重新验证类别、ROI、遮罩、输入输出、后处理、数据集表现和手机运行，再同步 manifest 及相应代码中的资产契约。不能只改散列跳过检查，也不能沿用旧模型的准确率结论。

## 已知覆盖局限

历史 v5 新视频的 52 张留出帧为 49 正确、3 拒识、0 接受错误输出，但来自同一录像分段，不代表独立新对局泛化。新素材只覆盖转换者无镜、L-STAR 无镜/3 倍、猎兽 2 倍、CAR 2 倍；复仇女神仍仅有很短的 2–4 倍镜训练片段。未出现的组合和未知枪械拒识都不能视为已验证。
