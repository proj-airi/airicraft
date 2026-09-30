workspace "Airicraft Survival Reflex" "Current architecture of the survival-reflex safety subsystem and its takeover/handback flows." {
    !identifiers hierarchical

    model {
        !impliedRelationships false

        minecraft = softwareSystem "Minecraft Client" "Provides the live player, world, entity, damage, and interaction APIs in which Airicraft runs." "External"
        navigation = softwareSystem "Navigation" "Provides exact-goal navigation for bounded underwater escape candidates." "External"
        llmProvider = softwareSystem "Planner LLM Provider" "Returns the planner decision made after a reflex resolves." "External"

        airicraft = softwareSystem "Airicraft" "A Fabric client mod that perceives, plans, and safely acts in Minecraft." {
            !docs README.md
            properties {
                "structurizr.inspection.model.softwaresystem.decisions" "ignore"
            }

            fabric = container "Fabric Client Mod" "Runs the embodied agent and survival-reflex subsystem on Minecraft client ticks." "Java 21, Fabric" {
                ingress = component "Tick & Damage Ingress" "Correlates damage sources with health loss and delivers tick-owned Minecraft observations." "Java" "Sensing" {
                    properties {
                        "source" "src/client/java/ai/moeru/airicraft/agent/LocalDamageTracker.java; src/client/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntime.java:onClientTick,onPlayerDamageObserved,onPlayerHealthUpdated"
                    }
                }

                coordinator = component "Embodied Runtime Coordinator" "Orders reflex evaluation before normal work and owns takeover, pause, and handback orchestration." "Java" "Coordination" {
                    properties {
                        "source" "src/client/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntime.java"
                    }
                }

                reflex = component "Survival Reflex Engine" "Owns danger memory, the IDLE/ACTIVE/AWAITING_PLANNER state machine, safety epochs, holds, action selection, and reflex events." "Java" "Safety" {
                    properties {
                        "source" "src/client/java/ai/moeru/airicraft/agent/reflex/SurvivalReflexRuntime.java; src/client/java/ai/moeru/airicraft/agent/reflex/SurvivalReflexSnapshot.java"
                    }
                }

                normalWork = component "Normal Work Runtimes" "Own active jobs, foreground action graphs, world tasks, following, and their paused-by-reflex state." "Java" "Execution" {
                    properties {
                        "source" "src/client/java/ai/moeru/airicraft/agent/job/ActiveJobRuntime.java; src/client/java/ai/moeru/airicraft/agent/actions/ActionGraphCoordinator.java; src/client/java/ai/moeru/airicraft/agent/tasks/WorldTaskExecutor.java"
                    }
                }

                effectors = component "Reflex Effectors" "Translate SWIM_TO_AIR, REACH_SAFE_LAND, STAY_AFLOAT, DEFEND, and FLEE into navigation, movement, view, and attack effects." "Java, Minecraft API" "Execution,Safety" {
                    properties {
                        "source" "src/client/java/ai/moeru/airicraft/agent/control/MovementController.java; src/client/java/ai/moeru/airicraft/agent/control/CameraController.java; src/client/java/ai/moeru/airicraft/agent/tasks/MinecraftUnderwaterEscapeController.java; src/client/java/ai/moeru/airicraft/agent/tasks/UnderwaterEscapeNavigator.java"
                    }
                }

                eventPipeline = component "Semantic Event Pipeline" "Stores reflex lifecycle events, applies routing policy, and emits a planner trigger only for reflex resolution." "Java" "Events" {
                    properties {
                        "source" "src/client/java/ai/moeru/airicraft/agent/events/AgentEventPipeline.java; src/client/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntime.java:processSurvivalReflexEvents,createReflexResolvedTrigger"
                    }
                }

                planner = component "Dialogue & Planner Runtime" "Receives the consolidated survival update and chooses whether to resume, replace, or cancel interrupted work." "Java, LLM" "Planning" {
                    properties {
                        "source" "src/client/java/ai/moeru/airicraft/agent/dialogue/DialogueRuntime.java; src/client/java/ai/moeru/airicraft/agent/llm/PlannerOrchestrator.java"
                    }
                }

                toolGate = component "Planner Tool Gate" "Blocks unsafe tools during ACTIVE and validates exact-hold resume_task requests after resolution." "Java" "Safety,Planning" {
                    properties {
                        "source" "src/client/java/ai/moeru/airicraft/agent/EmbodiedPlannerActionToolExecutor.java; src/client/java/ai/moeru/airicraft/agent/llm/PlannerToolCatalog.java"
                    }
                }

                config = component "Reflex Configuration" "Loads enablement and the low-air, minimum-defence-health, and threat-cooldown thresholds." "YAML, Java" "Configuration" {
                    properties {
                        "source" "src/client/java/ai/moeru/airicraft/agent/AgentConfig.java:ReflexConfig; src/client/java/ai/moeru/airicraft/agent/AgentConfigLoader.java"
                    }
                }
            }
        }

        minecraft -> airicraft "Supplies client ticks and live world observations to" "Fabric/Mixin callbacks"
        airicraft -> minecraft "Controls the local player through" "Minecraft client API"
        airicraft -> navigation "Delegates selected escape navigation to" "In-process API"
        airicraft -> llmProvider "Requests post-reflex planning decisions from" "Configured planner backend"

        minecraft -> airicraft.fabric "Supplies client ticks and live world observations to" "Fabric/Mixin callbacks"
        airicraft.fabric -> minecraft "Reads world state and controls the local player through" "Minecraft client API"
        airicraft.fabric -> navigation "Delegates selected escape navigation to" "In-process API"
        airicraft.fabric -> llmProvider "Requests post-reflex planning decisions from" "Configured planner backend"

        minecraft -> airicraft.fabric.ingress "Emits client ticks, damage sources, and health changes to" "Fabric/Mixin callbacks"
        airicraft.fabric.ingress -> airicraft.fabric.coordinator "Delivers tick-owned and correlated damage observations to" "In-process call"
        airicraft.fabric.coordinator -> airicraft.fabric.reflex "Ticks, resumes, releases, discards, and resets" "In-process call"
        airicraft.fabric.reflex -> airicraft.fabric.coordinator "Returns safety decisions, release requests, snapshots, and lifecycle events to" "In-process return/callback"
        airicraft.fabric.reflex -> minecraft "Reads player air, health, hostile entities, line of sight, and terrain from" "Minecraft client API"
        airicraft.fabric.coordinator -> airicraft.fabric.normalWork "Releases actuators and pauses, resumes, replaces, or cancels interrupted work in" "In-process call"
        airicraft.fabric.reflex -> airicraft.fabric.effectors "Commands the selected survival action through" "In-process call"
        airicraft.fabric.effectors -> minecraft "Applies movement, view, attack, and interaction effects to" "Minecraft client API"
        airicraft.fabric.effectors -> navigation "Starts, polls, and releases exact escape navigation through" "In-process API"
        airicraft.fabric.coordinator -> airicraft.fabric.eventPipeline "Appends reflex lifecycle events to" "In-process call"
        airicraft.fabric.eventPipeline -> airicraft.fabric.planner "Creates a consolidated trigger after reflex resolution for" "Semantic event"
        airicraft.fabric.coordinator -> airicraft.fabric.planner "Updates safety epoch, hold identity, and active-reflex context in" "In-process call"
        airicraft.fabric.planner -> llmProvider "Requests a resume, replace, or cancel decision from" "Configured planner backend"
        llmProvider -> airicraft.fabric.planner "Returns the selected decision to" "Configured planner backend"
        airicraft.fabric.planner -> airicraft.fabric.toolGate "Executes the selected planner tool through" "In-process call"
        airicraft.fabric.toolGate -> airicraft.fabric.coordinator "Requests validated resume, replacement, cancellation, or safe inspection from" "In-process call"
        airicraft.fabric.config -> airicraft.fabric.reflex "Supplies enablement and danger/action thresholds to" "Construction-time configuration"
    }

    views {
        systemContext airicraft "reflex-context" "Where the reflex subsystem sits relative to Minecraft, Navigation, and the planner provider." {
            include *?
            autoLayout lr
        }

        container airicraft "reflex-runtime" "Which running Airicraft application owns reflex behavior and which runtime boundaries it crosses." {
            include *?
            autoLayout lr
        }

        component airicraft.fabric "reflex-components" "Which runtime responsibilities sense danger, own safety state, preempt work, actuate recovery, and hand control back." {
            include *?
            autoLayout tb 300 250
        }

        dynamic airicraft.fabric "reflex-drowning-takeover" "How low air or drowning damage takes over actuation and reaches a stable breathable or safe-standing state." {
            minecraft -> airicraft.fabric.ingress "Emits a low-air tick or correlated drowning damage"
            airicraft.fabric.ingress -> airicraft.fabric.coordinator "Delivers the current player/world observation"
            airicraft.fabric.coordinator -> airicraft.fabric.reflex "Ticks with interrupted job/action identities"
            airicraft.fabric.reflex -> airicraft.fabric.coordinator "Enters ACTIVE, allocates safety epoch/hold, and requests normal actuator release"
            airicraft.fabric.coordinator -> airicraft.fabric.normalWork "Releases current effects and records PAUSED_BY_REFLEX"
            airicraft.fabric.reflex -> airicraft.fabric.effectors "Selects SWIM_TO_AIR for interrupted work or REACH_SAFE_LAND when idle"
            airicraft.fabric.effectors -> navigation "Tries exact navigation to a bounded breathable/safe-standing candidate"
            airicraft.fabric.effectors -> minecraft "Falls back to route waypoints or swim-up movement and view control"
            airicraft.fabric.reflex -> minecraft "Rechecks air recovery and verified safe-standing state for 12 stable ticks"
            airicraft.fabric.reflex -> airicraft.fabric.coordinator "Resolves to IDLE or AWAITING_PLANNER and emits the lifecycle event"
            airicraft.fabric.coordinator -> airicraft.fabric.eventPipeline "Records the complete safety episode"
            autoLayout lr
        }

        dynamic airicraft.fabric "reflex-mob-takeover" "How proximity or correlated mob damage selects immediate defence/flee effects and resolves after threats clear." {
            minecraft -> airicraft.fabric.ingress "Emits a client tick or correlated mob damage"
            airicraft.fabric.ingress -> airicraft.fabric.coordinator "Delivers the observation"
            airicraft.fabric.coordinator -> airicraft.fabric.reflex "Ticks with current interrupted-work identity"
            airicraft.fabric.reflex -> minecraft "Resolves remembered attackers and visible hostiles within the threat horizon"
            airicraft.fabric.reflex -> airicraft.fabric.coordinator "Enters ACTIVE and requests normal actuator release"
            airicraft.fabric.coordinator -> airicraft.fabric.normalWork "Releases current effects and records PAUSED_BY_REFLEX"
            airicraft.fabric.reflex -> airicraft.fabric.effectors "Auto-attacks in melee range; DEFENDs against one ordinary mob and FLEEs from overwhelming or special threats"
            airicraft.fabric.effectors -> minecraft "Attacks when ready or applies direct away-vector sprint/jump movement"
            airicraft.fabric.reflex -> minecraft "Rechecks live threats and the recent-damage cooldown"
            airicraft.fabric.reflex -> airicraft.fabric.coordinator "Resolves after no relevant threat remains and the cooldown expires"
            airicraft.fabric.coordinator -> airicraft.fabric.eventPipeline "Records the complete safety episode"
            autoLayout lr
        }

        dynamic airicraft.fabric "reflex-planner-handback" "How a resolved reflex gives the planner one correlated decision and resumes only the exact held task." {
            airicraft.fabric.reflex -> airicraft.fabric.coordinator "Emits reflex.resolved with safetyEpoch, holdId, cause, action, and next state"
            airicraft.fabric.coordinator -> airicraft.fabric.eventPipeline "Appends and routes the semantic event"
            airicraft.fabric.coordinator -> airicraft.fabric.planner "Updates the current safety epoch/hold context"
            airicraft.fabric.eventPipeline -> airicraft.fabric.planner "Creates one consolidated survival-reflex-resolved planner trigger"
            airicraft.fabric.planner -> llmProvider "Requests a resume, replace, or cancel decision"
            llmProvider -> airicraft.fabric.planner "Returns the selected decision"
            airicraft.fabric.planner -> airicraft.fabric.toolGate "Calls resume_task with the exact holdId, or selects replacement/cancellation"
            airicraft.fabric.toolGate -> airicraft.fabric.coordinator "Rejects ACTIVE/stale/no-hold resumes or forwards the valid decision"
            airicraft.fabric.coordinator -> airicraft.fabric.reflex "Releases the matching AWAITING_PLANNER hold"
            airicraft.fabric.reflex -> airicraft.fabric.coordinator "Returns IDLE and emits reflex.hold_released"
            airicraft.fabric.coordinator -> airicraft.fabric.normalWork "Resumes the blocked active job, or replaces/cancels held work"
            autoLayout lr
        }

        styles {
            element "Element" {
                color #455A64
                stroke #455A64
                fontSize 24
            }
            element "Software System" {
                background #1565C0
                color #FFFFFF
            }
            element "Container" {
                background #1976D2
                color #FFFFFF
            }
            element "External" {
                background #ECEFF1
                color #263238
            }
            element "Safety" {
                background #C62828
                color #FFFFFF
            }
            element "Sensing" {
                background #6A1B9A
                color #FFFFFF
            }
            element "Coordination" {
                background #283593
                color #FFFFFF
            }
            element "Execution" {
                background #EF6C00
                color #FFFFFF
            }
            element "Events" {
                background #00838F
                color #FFFFFF
            }
            element "Planning" {
                background #2E7D32
                color #FFFFFF
            }
            element "Configuration" {
                background #546E7A
                color #FFFFFF
            }
            relationship "Relationship" {
                color #546E7A
                thickness 2
                fontSize 18
            }
        }
    }

    configuration {
        scope softwaresystem
    }
}
