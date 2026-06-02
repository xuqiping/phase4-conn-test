# AI Fundamentals Workflow

## Purpose

基于基础AI理论知识体系，为用户提供机器学习（监督/无监督/强化）、深度学习（CNN/RNN/Transformer/Diffusion）、神经网络基础（感知机→GNN/SNN）、概率论与数理统计（生成模型/贝叶斯推断/A/B测试/模型校准）等核心理论的技术解答、学习路径规划或面试辅导。覆盖预训练模型+微调范式、交叉验证金标准、概率校准必要性等关键洞察。

## Prerequisites

- 用户已明确基础AI理论场景或问题
- 知识库文件 `05_人工智能与机器学习.md` 及子目录文件可访问

## Steps

### Step 1: 识别基础AI理论需求场景

**Goal**: 明确用户的理论基础需求类型、应用场景和当前水平
**Completion criterion**: 已确定场景标签、理论方向、应用场景和水平等级

1. 读取用户消息，提取以下信息：
   - 场景类型：理论学习 / 面试辅导 / 模型选型指导 / 算法优化 / 数学基础补课
   - 理论方向：机器学习（监督/无监督/强化） / 深度学习（CNN/RNN/Transformer/Diffusion） / 神经网络基础（感知机/激活函数/GNN/SNN） / 概率统计（贝叶斯/假设检验/A/B测试/校准）
   - 应用场景：数据建模 / 模型设计 / 训练优化 / 不确定性量化 / 生成模型 / 风险评估
   - 当前水平：入门（需要概念理解） / 中级（能应用现有模型） / 高级（能设计新架构/优化算法）
   - 具体目标：如"准备大厂算法面试"、"理解Transformer注意力机制"、"设计A/B测试框架"、"模型概率校准方法"

2. 对照知识库中的关键洞察初步判断：
   - 模型训练相关（如何训练/优化/评估）→ 机器学习+深度学习基础
   - 模型架构相关（如何选择/设计网络结构）→ 神经网络基础+深度学习
   - 不确定性/生成/推断相关 → 概率统计+生成模型
   - 高风险场景（医疗/金融）→ 概率校准+交叉验证+不确定性量化

3. 如有信息缺失，向用户追问不超过2个澄清问题。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/05_人工智能与机器学习.md > 基础AI理论]
- [参考: Agents知识库/0_超级编程行业知识库/05_人工智能与机器学习.md > 各L2摘要 > 基础AI理论]

### Step 2: 输出基础AI理论方案

**Goal**: 产出针对性的理论解答、学习路径或模型选型指导
**Completion criterion**: 输出包含核心概念解释、方法论、实践建议、学习资源

根据Step 1确定的场景，按以下分支处理：

**分支A — 机器学习基础**：
1. 输出监督学习核心概念：回归（线性/多项式/岭回归/Lasso）vs 分类（逻辑回归/SVM/决策树/随机森林/GBDT/XGBoost/LightGBM/CatBoost），附适用场景对比表。
2. 输出无监督学习核心概念：聚类（K-Means/层次聚类/DBSCAN/高斯混合）vs 降维（PCA/t-SNE/UMAP）vs 关联规则，附评估指标（轮廓系数/SSE/互信息）。
3. 输出强化学习基础：MDP马尔可夫决策过程、Q-Learning/DQN策略、价值函数vs策略梯度、探索与利用权衡。
4. 给出预训练模型+微调范式说明：为何几乎所有场景都应使用预训练模型+微调而非从头训练（数据效率、收敛速度、泛化能力）。
5. 附经典面试题：偏差-方差分解、过拟合与欠拟合诊断、正则化方法（L1/L2/Dropout/Early Stopping）、交叉验证策略（K-Fold/Stratified/Time Series Split）。

**分支B — 深度学习架构**：
1. 输出CNN卷积神经网络：卷积层（局部连接/权值共享）、池化层、经典架构演进（LeNet→AlexNet→VGG→ResNet→DenseNet→EfficientNet），附感受野计算和参数量对比。
2. 输出RNN循环神经网络：LSTM（门控机制：输入门/遗忘门/输出门）vs GRU（简化门控）、双向RNN、序列到序列（Seq2Seq+Attention），附梯度消失/爆炸问题与解决方案。
3. 输出Transformer架构：自注意力机制（Q/K/V矩阵、Scaled Dot-Product Attention、多头注意力）、位置编码（正弦/学习式/RoPE）、编码器-解码器结构、LayerNorm+残差连接，附复杂度分析（O(n²)自注意力 vs O(n)线性注意力）。
4. 输出Diffusion扩散模型：前向扩散（加噪过程）、反向去噪（U-Net预测噪声）、DDPM/DDIM采样加速、Stable Diffusion架构（Latent Diffusion+VAE），附与GAN/VAE/Flow-based模型的对比。
5. 附训练优化技巧：学习率调度（Warmup+Cosine Decay）、优化器对比（SGD/Momentum/Adam/AdamW/LAMB）、梯度裁剪、混合精度训练（FP16/BF16）、分布式训练（Data Parallel/Model Parallel/Pipeline Parallel/ZeRO）。

**分支C — 神经网络基础**：
1. 输出从感知机到深度网络：感知机（线性可分）、多层感知机（MLP、非线性激活函数Sigmoid/ReLU/GELU/Swish）、反向传播算法（链式法则、梯度计算）。
2. 输出激活函数选型：ReLU（稀疏性、计算简单、Dead ReLU问题）→ Leaky ReLU/ELU/GELU（平滑负半轴）→ Swish/Mish（自门控、平滑非单调），附各激活函数的导数特性和适用场景。
3. 输出图神经网络GNN：图卷积（GCN谱域方法）、图注意力（GAT）、消息传递框架（Message Passing Neural Network）、图分类/节点分类/链接预测任务，附与CNN在规则网格上的关系。
4. 输出脉冲神经网络SNN：生物启发的第三代神经网络、时间编码（Rate/Temporal/Population编码）、STDP学习规则、神经形态芯片（Intel Loihi/IBM TrueNorth），附与ANN的能效对比。

**分支D — 概率统计与模型评估**：
1. 输出贝叶斯推断：先验分布选择（共轭先验/无信息先验）、似然函数、后验推断（解析解/MCMC/变分推断）、贝叶斯优化（高斯过程+采集函数），附与频率学派的对比。
2. 输出生成模型数学基础：VAE（变分下界ELBO、重参数化技巧）、GAN（极小极大博弈、JS散度/Wasserstein距离）、Normalizing Flow（可逆变换、对数似然计算），附各生成模型的优缺点对比。
3. 输出A/B测试框架：实验设计（随机化/分层/桶化）、样本量计算（统计功效/显著性水平/最小可检测效应）、指标选择（率指标/均值指标/比率指标）、结果分析（t检验/卡方检验/Bootstrap），附常见陷阱（网络效应/新奇效应/幸存者偏差）。
4. 输出概率校准：Platt Scaling（Sigmoid拟合）、Isotonic Regression（单调回归）、Temperature Scaling（温度缩放），附Brier Score和Expected Calibration Error（ECE）评估，强调高风险场景（医疗/金融）概率校准是部署必要条件。
5. 附交叉验证金标准：K-Fold（K=5/10）、Stratified K-Fold（类别不平衡）、Time Series Split（时序数据防泄露）、Group K-Fold（组间独立），附何时用Holdout vs CV的决策树。

将结果保存到 `output/ai_fundamentals.md` 或直接在对话中呈现。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/05_人工智能与机器学习.md > 各L2摘要 > 基础AI理论]
- [参考: Agents知识库/0_超级编程行业知识库/人工智能与机器学习/基础AI理论.md > 机器学习/深度学习/神经网络基础/概率统计]

### Step 3: 验证与交付

**Goal**: 确保理论解释准确无误、与知识库一致
**Completion criterion**: 已通过 checklist 逐项核对

1. 读取 `checklist/ai_fundamentals_workflow_checklist.md`。
2. 逐项核对输出是否覆盖要求的知识点。
3. 确认数学公式、算法复杂度、经典论文结论等精确数据已核对。
4. 如有遗漏或偏差，补充修正。
5. 向用户交付最终答案。

## Post-Workflow

1. 记录完成状态到 `task/current_task.md`。
2. 如需深入某一具体理论（如"Transformer注意力机制矩阵计算细节"、"贝叶斯优化采集函数对比"），在当前 Agent 内继续追问并输出。