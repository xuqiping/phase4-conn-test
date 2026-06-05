# CV & Speech Workflow Checklist

在完成 `workflow/cv_speech_workflow.md` 的每一步后，使用此检查清单进行交叉验证。每个项目必须回答**是**才算完成。如果有任何项目回答**否**，修复输出并重新验证。

## Step 1: 识别计算机视觉/语音需求场景

- [ ] 已明确场景类型（目标检测/图像分割/语音识别合成/AIGC生成/多模态VLM/工业质检）
- [ ] 已识别任务类型（实时推理毫秒级/批量处理离线/交互式人机对话）
- [ ] 已提取部署环境（云端GPU集群/边缘设备Jetson树莓派/移动端iOS Android/浏览器WebGL WebGPU）
- [ ] 已提取数据特点（图片数量分辨率/视频时长帧率/音频采样率语种方言/数据标注质量）
- [ ] 已提取合规要求（内容审核/水印标识GB 45438-2025/隐私保护人脸脱敏）
- [ ] 已对照知识库技术选型完成初步判断（实时检测→YOLO/通用分割→SAM2/3/长时语音→SpeechLLM/文生视频→可灵即梦/多模态→VLM）
- [ ] 如有信息缺失，已向用户追问不超过2个澄清问题

## Step 2: 输出计算机视觉/语音方案

- [ ] 如为目标检测，YOLO系列演进与选型已输出（v8/v9/v10/v11对比/速度精度模型大小/适用场景/部署优化TensorRT ONNX OpenVINO NCNN RKNN）
- [ ] 如为目标检测，训练数据策略已覆盖（数据增强Mosaic MixUp/标注质量/迁移学习COCO→领域微调）
- [ ] 如为目标检测，评估与优化已说明（mAP FPS F1混淆矩阵/错误案例分析）
- [ ] 如为图像分割，SAM系列能力矩阵已覆盖（SAM 1图像/SAM 2视频/SAM 3 3D/适用场景）
- [ ] 如为图像分割，传统分割模型对比已输出（U-Net/DeepLabV3+/SegFormer/Mask R-CNN/YOLACT/RT-DETR/Panoptic FPN）
- [ ] 如为语音处理，ASR选型已覆盖（Whisper/FunASR/Paraformer/端到端SpeechLLM VibeVoice/Qwen-Audio/SpeechGPT）
- [ ] 如为语音处理，TTS选型已输出（FastSpeech 2/VITS/Bark/F5-TTS/CosyVoice/GPT-SoVITS）
- [ ] 如为语音处理，语音交互架构已说明（全双工/车载场景/客服场景/噪声抑制/离线ASR）
- [ ] 如为AIGC生成，文生图技术栈已覆盖（DiT/U-Net/ControlNet/LoRA微调/国产可灵即梦通义万相混元）
- [ ] 如为AIGC生成，文生视频技术栈已输出（基于DiT原生视频/世界模型/时序注意力/3D VAE/运动先验/国产超越Sora）
- [ ] 如为AIGC生成，合规与内容安全已说明（GB 45438-2025水印标识/内容审核/版权保护）
- [ ] 如为多模态VLM，选型对比已输出（闭源GPT-4V/Claude 3/Gemini/开源Qwen-VL/InternVL/LLaVA/MiniCPM-V）
- [ ] 如为多模态VLM，典型应用场景已覆盖（文档理解/视觉问答/视频分析）
- [ ] 如为多模态VLM，端侧部署已说明（INT4/INT8量化/MNN/NCNN/TFLite/CoreML/NPU适配）
- [ ] 所有核心论断均能在知识库中找到支撑来源

## Step 3: 验证与交付

- [ ] 已读取对应 checklist 并逐项核对
- [ ] 模型性能数据（YOLO v11 mAP/可灵vs Sora等）准确
- [ ] 已向用户交付最终答案

## Overall

- [ ] 工作流中的所有步骤已按顺序执行，没有跳过
- [ ] 每一步都已与其检查清单部分进行交叉验证
- [ ] 没有在任何检查清单部分通过前提前进入下一步
- [ ] `task/current_task.md` 已更新完成记录
- [ ] 所有 `[参考: ...]` 标注均指向存在的知识库文件
