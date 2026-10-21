# System 1.5 literature survey

Survey date: 2026-09-24. Scope: the evidence the [proposal](proposal.md) depends on, grouped by the question it
answers. Each entry says what we take from it and how the claim was checked.

**Verification key**

- **P**: primary source read in this session (text or code fetched from GitHub raw mirrors or PyPI wheels).
- **S**: title, authors and headline claims checked against search-engine abstract summaries; full text not read.
- **K**: established prior work cited from background knowledge; arXiv ID spot-checked by search.

The survey environment's network policy blocked arxiv.org, huggingface.co, alphaxiv.org, aclanthology.org and
ai.google.dev, so most 2025–2026 papers are **S**. The eight papers marked ★ carry the most weight in design decisions;
read their full text before starting Phase 1 (see [Reading list](#reading-list-before-phase-1)).

## Q1. Is "the whole state in one structured message, no conversation" viable?

The old Airicraft paradigm has become a mainstream research direction for long-horizon agents: keep a compact,
self-rewritten state and discard transcript history. None of these works is real-time or embodied at game rate.

| Work | Finding | Use here | Check |
| --- | --- | --- | --- |
| ★ MEM1 ([2506.15841](https://arxiv.org/abs/2506.15841), ICLR 2026) | RL-trained agent keeps a compact internal state it rewrites each turn, discarding prior context; memory stays near-constant over long horizons, and the consolidation behaviour is learned end to end from task reward rather than engineered. | Evidence that "state as prompt" trains well and needs RL or distillation, not prompting alone. | S |
| MemAgent ([2507.02259](https://arxiv.org/abs/2507.02259), ICLR 2026 oral) | Fixed-length memory overwritten segment by segment, trained with multi-conversation RL; 8K context extrapolates to 3.5M-token QA with <5% loss. | Overwrite-in-place memory is learnable; supports fixed-width slots. | S |
| Context-Folding ([2510.11967](https://arxiv.org/abs/2510.11967)) | Folding agent matches or beats ReAct with a 10x smaller active context. | Bounded context is not a quality tax. | S |
| VISTA ([2606.30005](https://arxiv.org/abs/2606.30005), 2026) | Working memory as typed addressable blocks plus a dashboard of size/recency/access ("state proprioception"); training-free; Gemini-3-Flash 22.7% to 50.7% on LOCA-Bench. | Typed sections with metadata (owner, age, confidence) in the state document. | S |
| RecurrentGPT ([2305.13304](https://arxiv.org/abs/2305.13304)) | LLM simulates an LSTM with natural-language memory in the prompt; memory is human-readable and editable. | "Legible recurrence" precedent; operator-editable state. | S |
| MemGPT ([2310.08560](https://arxiv.org/abs/2310.08560)) | Self-editing core-memory blocks via tool calls. | The autoregressive way of writing fields; our AR baseline's `update` mode. | K |
| CoALA (Sumers et al., [2309.02427](https://arxiv.org/abs/2309.02427)) | Working memory as the agent's structured state; decision cycle over it. | Vocabulary for sections and decision cycle. | K |
| Sleep-time compute (Lin et al., Letta, [2504.13171](https://arxiv.org/abs/2504.13171)) | Pre-computing inferences about a context while idle cuts test-time compute ~5x at equal accuracy; amortizes 2.5x across queries. | Refresh the READING during idle ticks so it is ready before it is needed. | S |
| Airicraft ADR 0002 + `PlannerSnapshotPresentation` | The planner request already carries a state baseline followed by `stateChanges` deltas, replaces tool results in place, and keeps `TOOL QUEUE` as a snapshot. | The codebase has partly converged on this paradigm inside the conversation. | P (repo) |

## Q2. Which diffusion language models exist, and how capable and fast are they?

| Work | Finding | Use here | Check |
| --- | --- | --- | --- |
| ★ **DiffusionGemma** (Google DeepMind, released 2026-06-10, Apache 2.0; tech report [2608.00146](https://arxiv.org/abs/2608.00146)) | Gemma 4 26B-A4B MoE (25.2B total, 3.8B active) fine-tuned to block diffusion with <10% of the AR token budget (SFT, then RL + sampler distillation). Causal encoder writes the prompt KV cache; bidirectional decoder denoises a **256-token canvas** from **uniform random tokens**; keeps thinking mode, image/video input, 256K context, function calling. MMLU-Pro 77.6 vs 82.6 (AR Gemma 4 26B), GPQA-D 73.2 vs 82.3, LiveCodeBench v6 69.1 vs 77.1. | Primary model for T1/T2. The matched AR parent (`google/gemma-4-26B-A4B-it`) makes AR-vs-diffusion a clean ablation. | P (mechanics, APIs) + S (benchmarks) |
| DiffusionGemma in vLLM ([vLLM blog, 2026-06-10](https://github.com/vllm-project/vllm-project.github.io/blob/main/_posts/2026-06-10-diffusion-gemma.md)) | Encoder/decoder modes share weights; vLLM automatic prefix caching works unchanged; entropy-bound acceptance, convergence when argmax is stable and mean entropy is below threshold; **FP8 at batch 1: 1,008 tok/s on H100, 1,288 on H200** (~5–6x an AR baseline, ~2.6–3x AR with MTP). FP8 and NVFP4 checkpoints on RedHatAI. | Latency expectations for E1; cold-start serving path. | P |
| DiffusionGemma in transformers/diffusers ([transformers docs](https://github.com/huggingface/transformers/blob/main/docs/source/en/model_doc/diffusion_gemma.md), [diffusers docs](https://github.com/huggingface/diffusers/blob/main/docs/source/en/api/pipelines/diffusion_gemma.md), transformers 5.11.0 source) | `decoder_input_ids` **sets a starting canvas** instead of random tokens; `DiffusionGemmaPipeline` exposes schedulers (`EntropyBoundScheduler` = released sampler, `BlockRefinementScheduler` with `editing_threshold` to re-edit committed tokens, `DiscreteDDIMScheduler` with a leave-one-out predictor-corrector) and a per-step callback that sees and may replace `canvas`/`logits`; adaptive stopping roughly halves decoder forwards; PEFT adapters load unmerged. | Warm start, clamping, per-step trajectories and LoRA are supported by public APIs; our loop mirrors the pipeline. | P |
| DiffusionGemma fine-tuning ([NeMo AutoModel guide](https://github.com/NVIDIA-NeMo/Automodel/blob/main/docs/guides/dllm/diffusiongemma.md)) | SFT recipe: uniform corruption t ~ U(0.001, 1), flat CE over **all** tokens of one canvas (corrupted and clean), plus encoder AR loss, self-conditioning p=0.5, frozen router; LoRA rank 16 on attention and dense MLP; FSDP2 with EP=8 on 8 GPUs. Unsloth also supports DiffusionGemma fine-tuning (docs; not read). | E5 recipe and data format. | P (NeMo) / S (Unsloth) |
| Neither Parallel Nor Sequential ([2606.14620](https://arxiv.org/abs/2606.14620)) | Instrumented DiffusionGemma commits: partial left-to-right bias depending on granularity; **structured JSON committed in essentially arbitrary order**; commit confidence tracks correctness on math but **carries no signal on factual recall**; commits come in a late burst. | JSON forms suit the model; confidence must be calibrated per slot type (E3). | S |
| How Transparent is DiffusionGemma? (Engels et al., DeepMind, [2606.20560](https://arxiv.org/abs/2606.20560)) | Not significantly less transparent than Gemma; logit lens works on intermediate states. | Intermediate canvases are meaningful to inspect (dashboard, signals). | S |
| NVIDIA NIM for DiffusionGemma | OpenAI-compatible container; free hosted prototyping on build.nvidia.com (NIM context 8K). | Quick zero-shot probes without a GPU (no warm start). | S |
| DiffusionGemma on Apple Silicon | Not loadable by stock mlx-lm; `mlx-community/diffusiongemma-26B-A4B-it-OptiQ-4bit` via OptiQ; speed advantage may vanish on Apple Silicon; llama.cpp support unmerged. | The warm-start experiments need a CUDA GPU; Mac only for qualitative checks. | S |
| LLaDA 8B (Nie et al., [2502.09992](https://arxiv.org/abs/2502.09992)) | Masked diffusion from scratch, competitive with LLaMA3 8B in-context; low-confidence remasking. | Background; masked (absorbing) family. | S |
| iLLaDA ([2606.25331](https://arxiv.org/abs/2606.25331)) | 8B MDM, 12T tokens, GQA; large gains over LLaDA; competitive with Qwen2.5 7B. | Alternative open masked model. | S |
| LLaDA-MoE-7B-A1B ([2509.24389](https://arxiv.org/abs/2509.24389)) | 1.4B active params; beats LLaDA-8B and Dream-7B; trajectory-distilled `-TD` variant for speed. | Small/fast masked alternative for B-series ablations. | S |
| LLaDA2.0 ([2512.15745](https://arxiv.org/abs/2512.15745)) / LLaDA2.1 ([2602.08676](https://arxiv.org/abs/2602.08676)) | AR-converted MoE dLLMs (16B mini, 100B flash); 2.1 adds token-to-token **editing** alongside mask-to-token, and RL for dLLMs; 892 tok/s HumanEval+ at 100B. | Second model family with native editing; dInfer/SGLang serving. | S |
| Dream 7B ([2508.15487](https://arxiv.org/abs/2508.15487)) | AR-initialised (Qwen2.5) masked diffusion with context-adaptive noise rescheduling; infilling and planning strengths. | Background; comparison family. | S |
| Mercury / Mercury 2 (Inception, [2506.17298](https://arxiv.org/abs/2506.17298)) | Commercial dLLMs, 1,100+ tok/s on H100 (Coder); API offers chat, `fim/completions` (prefix+suffix infill) and apply-edit endpoints; Mercury Edit 2 does next-edit prediction. | Hosted option for AR-style comparisons and single-gap infill; no canvas control. | S |
| Fast-dLLM v2 ([2509.26328](https://arxiv.org/abs/2509.26328)) / TiDAR ([2511.08923](https://arxiv.org/abs/2511.08923)) / dQwen3.5 ([2609.20751](https://arxiv.org/abs/2609.20751)) | Cheap AR-to-diffusion conversion (~1.3B tokens for 7B); diffusion drafting with AR verification in one forward; hybrid-attention Qwen3.5 (0.8B–9B) adapted with half the tokens. | Path to a small custom System 1.5 model if DiffusionGemma is too large. | S |
| BD3-LM Block Diffusion (Arriola et al., [2503.09573](https://arxiv.org/abs/2503.09573), ICLR 2025 oral) | Block size interpolates between AR and diffusion; KV caching across blocks. | Explains DiffusionGemma's causal-prefix + bidirectional-canvas design. | S |

## Q3. Can a diffusion model revise what it wrote, or only fill blanks?

Masked (absorbing-state) models cannot change a token once unmasked unless something remasks it. Uniform-noise
models, including DiffusionGemma, re-predict every position each step, so previously written values can be kept or
changed. This is the property warm start depends on.

| Work | Finding | Use here | Check |
| --- | --- | --- | --- |
| Mask-Predict (Ghazvininejad et al., [1904.09324](https://arxiv.org/abs/1904.09324)) | Iterative refinement by remasking lowest-confidence tokens. | Origin of confidence-based remasking. | K |
| ReMDM (Wang, Schiff, Sahoo, Kuleshov, [2503.00307](https://arxiv.org/abs/2503.00307)) | Remasking sampler for pretrained masked models; inference-time compute scaling; tokens become revisable. | Option if a masked model is used. | S |
| GIDD ([2503.04482](https://arxiv.org/abs/2503.04482)); Generalized Discrete Diffusion with Self-Correction ([2603.02230](https://arxiv.org/abs/2603.02230)) | Training on mixed masking + uniform noise teaches the model to judge and replace already-filled tokens. | Why uniform corruption matters for revising stale slots. | S |
| Uniform Diffusion Models Revisited ([2605.22765](https://arxiv.org/abs/2605.22765)) | Leave-one-out denoiser; predictor-corrector usable on released checkpoints without retraining (implemented in diffusers for DiffusionGemma). | Corrector sweeps as a cheap refresh step (E2 ablation). | S + P (diffusers impl.) |
| Persistent context in uniform discrete diffusion (Hayakawa, [2609.01043](https://arxiv.org/abs/2609.01043)) | Storing selected argmax tokens as persistent context for later predictions helps coordination; training-free. | Theoretical support for keeping settled slots visible across refreshes. | S |
| Edit Flows (Havasi et al., Meta, [2506.09018](https://arxiv.org/abs/2506.09018)) | Insert/delete/substitute as a CTMC; variable length. | Future: variable-length fields without fixed widths. | S |
| LLaDA2.1 T2T editing ([2602.08676](https://arxiv.org/abs/2602.08676)) | Token-to-token editing combined with mask filling in a released model. | Masked-family alternative with editing. | S |

## Q4. Warm start, continuous refresh and clamping: what transfers from continuous diffusion and robotics?

| Work | Finding | Use here | Check |
| --- | --- | --- | --- |
| SDEdit (Meng et al., [2108.01073](https://arxiv.org/abs/2108.01073)) | Partially noise an existing sample and denoise it to edit while keeping structure. | Warm start = SDEdit on the previous READING, noise only on dirty slots. | K |
| ★ Real-Time Chunking, RTC (Black, Galliker, Levine, [2506.07339](https://arxiv.org/abs/2506.07339), NeurIPS 2025) | Generate the next action chunk while executing the current one: **freeze** actions guaranteed to execute and **inpaint** the rest; works on any diffusion/flow policy without retraining; robust to inference delay. Follow-ups: native continuation ([2602.12978](https://arxiv.org/abs/2602.12978)), WarmPrior temporal priors ([2605.13959](https://arxiv.org/abs/2605.13959)), FutureRTC ([2607.24008](https://arxiv.org/abs/2607.24008)). | Closest precedent: clamp what is committed/owned, inpaint the rest, asynchronously with the world moving. | S |
| ★ Diffusion Forcing (Chen, Monso, Du, Simchowitz, Tedrake, Sitzmann, [2407.01392](https://arxiv.org/abs/2407.01392), NeurIPS 2024) | Train with **independent per-token noise levels**; sample as AR, full-sequence, or mixtures; supports planning with uncertain futures. | E6: per-slot noise by staleness/horizon ("sharp near, blurry far"). | S |
| Rolling Diffusion (Ruhe et al., [2402.09470](https://arxiv.org/abs/2402.09470)) | Sliding window with more noise further in the future. | Same idea for plan horizons. | S |
| GameNGen ([2408.14837](https://arxiv.org/abs/2408.14837)) | Diffusion Doom at ~20 FPS; **noise augmentation of context frames** (noise level as input) prevents autoregressive drift. | E5: corrupt the previous READING in training so self-conditioning on stale values does not drift. | S |
| StreamDiffusion ([2312.12491](https://arxiv.org/abs/2312.12491)) | Real-time pipelining; skips work when inputs are near-identical (similarity filter). | Skip refreshes when no section is dirty. | K |
| Discrete Diffusion VLA ([2508.20072](https://arxiv.org/abs/2508.20072)), Fast-dVLA ([2603.25661](https://arxiv.org/abs/2603.25661)), Dream-VLA ([2512.22615](https://arxiv.org/abs/2512.22615)) | Discrete diffusion over action tokens with secondary remasking for error correction; 30 Hz (Fast-dVLA); 11 Hz on an RTX 4090 (Dream-VLA). | Discrete diffusion reaches control rates on one GPU. | S |

## Q5. Structured output: do diffusion models keep a form valid?

| Work | Finding | Use here | Check |
| --- | --- | --- | --- |
| ★ The Bitter Lesson of dLLMs for Agentic Workflows ([2601.12979](https://arxiv.org/abs/2601.12979), ACL 2026; code `Coldmist-Lu/DiffuAgent`) | LLaDA/Dream **fail as agent backbones**: in embodied tasks (AgentBoard) they repeat the same action instead of branching on feedback; in tool calling (BFCL) they break strict JSON under diffusion noise. **Useful as non-causal auxiliary modules** (memory compression, tool filtering). | The strongest negative evidence. Design response: System 1.5 is an auxiliary state-filler, never the decision owner or tool caller; typed slots with snapping/clamping instead of free JSON calls; System 2 remains the planner. | S |
| Beyond Autoregression (Ye et al., [2410.14157](https://arxiv.org/abs/2410.14157), ICLR 2025) | "Subgoal imbalance": diffusion learns hard subgoals AR misses; Sudoku 100% vs 20.7%, Countdown 91.5% vs 45.8%. | Joint consistency across interdependent slots is where diffusion helps. | S |
| DINGO ([2505.23061](https://arxiv.org/abs/2505.23061)); CFG-constrained dLLM decoding ([2508.10111](https://arxiv.org/abs/2508.10111), ICLR 2026); finite-automata constrained decoding ([2607.07026](https://arxiv.org/abs/2607.07026)) | Regular/context-free constraints enforceable during parallel denoising with near-perfect syntactic adherence. | Enum slots can be enforced exactly; v0 uses fixed layouts + snapping, these are the upgrade path. | S |
| DiffusionGemma tech report / analysis (above) | Native structured JSON; order-independent JSON commits. | Zero-shot JSON forms are plausible for DiffusionGemma specifically (unlike LLaDA/Dream in the Bitter Lesson study). | S |

## Q6. Speed and caching for a document that mostly does not change

| Work | Finding | Use here | Check |
| --- | --- | --- | --- |
| Fast-dLLM ([2505.22618](https://arxiv.org/abs/2505.22618)) | Block-wise approximate KV cache for bidirectional models + confidence-aware parallel decoding; up to 27.6x throughput. | Background for masked models. | S |
| Flash-dLLM ([2609.26796](https://arxiv.org/abs/2609.26796)) | IO-aware fused KV cache, selective refresh of influential tokens, self draft-and-verify; 5.1x/11.0x over Elastic-Cache. | Background; selective refresh idea. | S |
| Affix Cache ([2608.26140](https://arxiv.org/abs/2608.26140)) | Reuse KV of shared spans beyond prefixes by recomputing ~20% "anchor" tokens; up to 55.7% lower recompute latency. | If sections other than the tail change, reuse the rest. | S |
| dInfer ([2510.08666](https://arxiv.org/abs/2510.08666)) | >1,100 tok/s at batch 1 (HumanEval, 8xH800); 10x over Fast-dLLM. | Serving option for LLaDA-family ablations. | S |
| EB-sampler ([2505.24857](https://arxiv.org/abs/2505.24857)) | Entropy-bounded unmasking: accept tokens whose joint entropy stays under a bound. | The released DiffusionGemma sampler; our loop implements it exactly (diffusers source). | P (impl.) |
| Prophet ([2508.19982](https://arxiv.org/abs/2508.19982), NeurIPS 2025) | Answers are identifiable at half the steps (GSM8K 97%, MMLU 99%); early commit on top-2 gap cuts steps up to 3.4x. | Warm refreshes should need few steps; commit early when settled. | S |
| transformers 5.11 `DynamicSlidingWindowLayer.crop` | Refuses to crop a sliding-window layer that has seen more tokens than its window. | KV-prefix reuse in the HF path only for prompts shorter than the window; measured separately in E1. | P |

## Q7. Can the fast layer tell when it does not know? (escalation to System 2)

| Work | Finding | Use here | Check |
| --- | --- | --- | --- |
| ★ Time Is a Feature (Wang et al., [2508.09138](https://arxiv.org/abs/2508.09138), ICLR 2026) | **Temporal oscillation**: correct answers appear mid-denoising and get overwritten; Temporal Semantic Entropy measures stability across steps; voting across steps and a stability reward help. | Flip count and temporal entropy per slot (`s15.signals`). | S |
| UQ for Large Language Diffusion Models ([2605.14570](https://arxiv.org/abs/2605.14570)) | Dissimilarity of intermediate states to the final output is the strongest single-pass uncertainty signal; hallucination detection near sampling-based methods at up to 100x lower compute. | `final_disagreement` signal. | S |
| Temporal Linguistic Emergence ([2604.23235](https://arxiv.org/abs/2604.23235)) | Calibration drifts during denoising: ECE 0.034 at step 0 rising to 0.415 at the final plateau. | Final-step confidence is overconfident; prefer trajectory signals; calibrate. | S |
| TRE ([2607.22661](https://arxiv.org/abs/2607.22661)), DeMTS ([2608.14632](https://arxiv.org/abs/2608.14632)), TDGNet ([2602.08048](https://arxiv.org/abs/2602.08048)) | Hallucination detection from entropy trajectories; late commitments are most indicative. | `late_entropy` signal. | S |
| Semantic entropy (Farquhar et al., Nature 2024) | Meaning-level uncertainty across samples detects confabulation. | Sampling baseline for the AR filler. | K |
| SOFAI (Booch et al., [2010.06002](https://arxiv.org/abs/2010.06002), AAAI 2021; metacognition [2110.01834](https://arxiv.org/abs/2110.01834)) | A metacognitive module chooses fast vs slow solvers by confidence, resources and past performance. | Escalation is a metacognitive decision; evaluate it as such (E3). | S |
| Drift-diffusion decision model (Ratcliff & McKoon 2008); urgency gating (Cisek, Puskas, El-Murr 2009) | Evidence accumulates to a bound; urgency lowers the bound over time. | Commit rule: slot settled k steps; lower k/threshold for safety slots. Analogy only. | K |
| EM-LLM ([2407.09450](https://arxiv.org/abs/2407.09450)) | Bayesian surprise segments experience into events online. | Surprise (prediction error) as a wake signal in E6. | S |

## Q8. Dual-process and blackboard architectures: what worked?

| Work | Finding | Use here | Check |
| --- | --- | --- | --- |
| ★ The Latent Bridge ([2606.24470](https://arxiv.org/abs/2606.24470), 2026) | Real-time Atari agents at 15 Hz: slow Qwen3-VL-8B-Thinking (~1.5 s/response) coupled to fast MiniCPM-o 4.5 by a **Text Bridge** (slow writes text the fast model reads) or a learned latent bridge. Latent wins on 2/7 games; **fast-only beats both bridges** on River Raid and SpaceInvaders. | Our blackboard is a text bridge. Always include a fast-only arm and measure whether coupling helps per scenario. | S |
| ★ AgileThinker / Real-Time Reasoning Gym (Wen et al., [2511.04898](https://arxiv.org/abs/2511.04898), ICLR 2026) | Planning thread reasons over frozen states while a reactive thread answers within the environment's update time; beats single-paradigm agents as time pressure rises. | Evaluation design: vary time pressure; sync (frozen) vs async runs (our E0c). | S |
| Talker-Reasoner (Christakopoulou, Mourad, Matarić, [2410.08328](https://arxiv.org/abs/2410.08328)) | Fast Talker reads the latest belief state (JSON) that the slow Reasoner writes to shared memory. | Same division of labour; we add continuous refresh and typed slots. | S |
| DPT-Agent ([2502.11882](https://arxiv.org/abs/2502.11882), ACL 2025) | System 1 = FSM + code-as-policy, System 2 = theory of mind + asynchronous reflection; first autonomous real-time human-AI collaboration in hard Overcooked. | Our System 1 is already code; 1.5 fills the gap DPT-Agent fills with asynchronous reflection. | S |
| Helix (Figure, 2025 blog) | S2 VLM at 7–9 Hz feeds a latent to S1 visuomotor policy at 200 Hz; trained end to end. | Rate targets: 1.5 at 2–10 Hz between S1 (20 Hz ticks) and S2 (seconds). | S |
| Hi Robot (Shi et al., [2502.19417](https://arxiv.org/abs/2502.19417), ICML 2025) | High-level VLM processes open-ended prompts and live user corrections into subgoals for a low-level policy. | Chat corrections ("stop", "not that") are exactly the 1.5 workload. | S |
| GR00T N1 ([2503.14734](https://arxiv.org/abs/2503.14734)) | VLM System 2 + diffusion-transformer System 1 action head. | Background. | K |
| SwiftSage ([2305.17390](https://arxiv.org/abs/2305.17390), NeurIPS 2023) | Small fine-tuned "Swift" model with LLM "Sage" fallback via heuristics. | Distilled fast model + escalation precedent. | S |
| Never Stop Thinking ([2609.17416](https://arxiv.org/abs/2609.17416), 2026) | Interrupt-and-resume orchestration gives continuous-time cognition in voice agents; −19% latency; ReactiveBench. | Interruption semantics when READING changes mid-S2 request. | S |
| Project Sid / PIANO (Altera, [2411.00114](https://arxiv.org/abs/2411.00114)) | Minecraft agents with concurrent modules (cognition, planning, motor, speech) reading/writing a **shared agent state**; a Cognitive Controller bottleneck keeps outputs coherent. | Closest Minecraft precedent for a shared state with concurrent writers; our ownership table is the coherence mechanism. | S |
| Parallelized planning-acting in Minecraft ([2503.03505](https://arxiv.org/abs/2503.03505), AAMAS 2026); time-sensitive collaboration in Minecraft ([2606.15684](https://arxiv.org/abs/2606.15684)); PillagerBench ([2509.06235](https://arxiv.org/abs/2509.06235)) | Interruptible dual-thread agents; synchronous fixed-timestep vs asynchronous modes show LLM latency causes task failures. | Our E0c latency-tax design. | S |
| Concurrent Modular Agent ([2508.19042](https://arxiv.org/abs/2508.19042)) | Framework for concurrent LLM modules. | Background. | S |
| Blackboard systems: Hearsay-II (Erman et al. 1980), Nii (AI Magazine 1986); Global Workspace (Baars 1988); shared workspace for neural modules (Goyal et al., [2103.01197](https://arxiv.org/abs/2103.01197), ICLR 2022) | Specialists coordinate through a shared structured store; limited-bandwidth workspaces force competition for access. | Theory for one document, many writers, typed slots as the bandwidth limit. | K |
| System-1.5 Reasoning ([2505.18962](https://arxiv.org/abs/2505.18962)) | Name collision: latent-space shortcuts in chain-of-thought. Unrelated. | Disambiguate in writing. | S |

## Q9. Training the fast layer from the slow one

| Work | Finding | Use here | Check |
| --- | --- | --- | --- |
| Distilling System 2 into System 1 (Yu, Xu, Weston, Kulikov, [2407.06023](https://arxiv.org/abs/2407.06023)) | System 2 outputs distilled into a model that answers without the intermediate reasoning, at lower cost. | E5: teacher READINGs from System 2-class models. | S |
| Expert Iteration (Anthony, Tian, Barber, [1705.08439](https://arxiv.org/abs/1705.08439)) | Apprentice (fast) learns from expert (slow search) in a loop. | Iterate: deploy 1.5, collect disagreements with System 2, relabel, retrain. | K |
| DAgger (Ross, Gordon, Bagnell, [1011.0686](https://arxiv.org/abs/1011.0686)) | Relabel on the learner's own state distribution to fix compounding errors. | E5 round 2: teacher labels on shadow-mode trajectories. | K |
| MDLMs as text-based world models ([2607.16204](https://arxiv.org/abs/2607.16204), 2026) | MDLMs model steerable state transitions with better coherence/groundedness than LLMs >4x larger at comparable latency; +47% abs zero-shot transfer when used for agent RL. | Diffusion is well suited to *state*; motivates "expected next events" slots (E6). | S |
| dLLM library ([2602.22661](https://arxiv.org/abs/2602.22661), `ZHZisZZ/dllm`); NaRA noise-aware LoRA ([2605.29716](https://arxiv.org/abs/2605.29716)) | Training recipes for LLaDA/Dream/BD3-LM/Edit Flows; LoRA tuned to noise levels. | Tooling if we move to a smaller open model. | S |

## Q10. Evaluation methodology

| Work | Finding | Use here | Check |
| --- | --- | --- | --- |
| rliable (Agarwal et al., [2108.13264](https://arxiv.org/abs/2108.13264), NeurIPS 2021) | Few-run evaluations need interval estimates, IQM, performance profiles. | Live scenario reporting (E4). | S |
| Response-time limits (Miller 1968; Nielsen 1993) | ~0.1 s feels instantaneous, ~1 s keeps flow, ~10 s loses attention. | Targets: chat acknowledgement < 1 s; current System 2 path is ~2–6 s. | K |

## Reading list before Phase 1

Read the full text (the survey only verified abstracts) of: MEM1, the DiffusionGemma tech report, The Bitter Lesson
of dLLMs for agentic workflows, Real-Time Chunking, Diffusion Forcing, Time Is a Feature, The Latent Bridge, and
AgileThinker. Allow arxiv.org and huggingface.co in the environment's network settings (or read locally) to do so.

## Claims the proposal relies on

| Claim | Evidence | Status |
| --- | --- | --- |
| A warm starting canvas is supported by public APIs | transformers docs (`decoder_input_ids`), diffusers callback that replaces `canvas` | Verified (P) |
| The warm-start regime is in-distribution enough to converge in few steps | Uniform-corruption training (NeMo recipe), revision literature (Q3), Prophet | **Untested: E1/E2 decide** |
| DiffusionGemma produces valid READING JSON zero-shot | Native structured output; JSON order-independence | **Untested: E2** |
| Refresh latency ≤ 250 ms p95 on one H100 | 1,008 tok/s at batch 1 (vLLM FP8); 256-token canvas; ~20 tokens per forward | **Untested: E1** (HF path is slower than vLLM) |
| Trajectory signals predict when System 2 is needed | Q7 literature, but confidence can be uninformative for recall | **Untested: E3** |
| A fast layer improves outcomes over fast-only and slow-only | Mixed: AgileThinker yes, Latent Bridge "fast-only" wins on some games | **Untested: E0c, E4** |
| dLLMs are poor decision owners/tool callers | Bitter Lesson study (LLaDA, Dream) | Accepted as a design constraint |
