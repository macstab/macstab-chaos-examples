# JVM Agent K8s Manifests

Kubernetes resources for running the chaos-agent examples in a cluster.

## Prerequisites

- Kubernetes 1.28+
- `kubectl` configured for your cluster
- `jq` installed locally for the watch commands
- KEDA 2.15+ (only for `04-virtual-thread-pinning-deployment.yaml`)
- Prometheus (only for `04-virtual-thread-pinning-deployment.yaml`)

## Files

| File | What it does |
|---|---|
| `01-basic-deployment-with-agent.yaml` | Basic Spring Boot 3 deployment with agent injected via init container; 50% socket latency + 5% executor rejection |
| `02-staging-chaos-configmap.yaml` | Comprehensive staging chaos plan: socket latency, JDBC faults, executor rejection, clock skew, ForkJoin delay |
| `03-rolling-update-rst-chaos-job.yaml` | Job that activates 30% TCP RST during a rolling update and verifies zero request drops |
| `04-virtual-thread-pinning-deployment.yaml` | Spring Boot 4 app with virtual threads + MonitorContention stressor; HPA on `chaos_virtual_thread_pinning_count` |

## Deploy

```bash
# Basic deployment with agent
kubectl apply -f 01-basic-deployment-with-agent.yaml

# Wait for rollout
kubectl rollout status deployment/sb3-jdbc-pool-deadlock

# Apply the staging chaos plan (update the ConfigMap then reload)
kubectl apply -f 02-staging-chaos-configmap.yaml
# The agent reloads within ~5 seconds — no pod restart needed

# Run the rolling-update RST scenario
kubectl apply -f 03-rolling-update-rst-chaos-job.yaml
kubectl logs -f job/rolling-update-rst-chaos

# Deploy the virtual-thread pinning example
kubectl apply -f 04-virtual-thread-pinning-deployment.yaml
kubectl rollout status deployment/sb4-impossible
```

## Live Observation

### Watch the chaos actuator on a running pod

```bash
POD=$(kubectl get pods -l app=sb3-jdbc-pool-deadlock -o jsonpath='{.items[0].metadata.name}')

# Stream the actuator state every 2 seconds
watch "kubectl exec $POD -- curl -s http://localhost:8080/actuator/chaos | jq"
```

### Trigger the deadlock scenario manually

```bash
kubectl exec $POD -- curl -s http://localhost:8080/trigger | jq
```

### Check the scheduler counter (module 2)

```bash
POD=$(kubectl get pods -l app=sb4-impossible -o jsonpath='{.items[0].metadata.name}')
kubectl exec $POD -- curl -s http://localhost:8080/scheduler/count | jq
```

## JVM Flight Recorder with Agent Active

Start a JFR recording on a live pod to capture JVM events during chaos:

```bash
POD=$(kubectl get pods -l app=sb3-jdbc-pool-deadlock -o jsonpath='{.items[0].metadata.name}')

# Get the PID of the JVM inside the container
PID=$(kubectl exec $POD -- jcmd | grep JdbcPoolDeadlockApplication | awk '{print $1}')

# Start a profiling JFR recording
kubectl exec $POD -- jcmd $PID JFR.start name=chaos settings=profile maxsize=128m

# After reproducing the scenario, dump and copy the recording
kubectl exec $POD -- jcmd $PID JFR.dump name=chaos filename=/tmp/chaos.jfr
kubectl cp $POD:/tmp/chaos.jfr ./chaos-$(date +%Y%m%d-%H%M%S).jfr

# Stop the recording
kubectl exec $POD -- jcmd $PID JFR.stop name=chaos
```

Open the `.jfr` file in JDK Mission Control to analyze lock contention, JDBC wait times, and GC pauses alongside chaos events.

## Dynamic Agent Attach to a Running Pod

Attach the chaos agent to a JVM that was started without the `-javaagent` flag.
Requires the agent tools jar to be present in the image at `/chaos/tools.jar`.

```bash
POD=$(kubectl get pods -l app=sb3-jdbc-pool-deadlock -o jsonpath='{.items[0].metadata.name}')

# Get the PID
PID=$(kubectl exec $POD -- jcmd | grep JdbcPoolDeadlockApplication | awk '{print $1}')

# Attach dynamically — the agent starts intercepting immediately
kubectl exec $POD -- java \
  -cp /chaos/tools.jar \
  com.sun.tools.attach.VirtualMachine \
  attach $PID /chaos/chaos-agent.jar

# Confirm the agent is active
kubectl exec $POD -- curl -s http://localhost:8080/actuator/chaos | jq .status
```

## Updating the Chaos Plan at Runtime

Edit the ConfigMap and apply it.  The agent polls the mounted file and reloads within 5 seconds without restarting the pod:

```bash
kubectl edit configmap chaos-plan -n default
# or
kubectl apply -f 02-staging-chaos-configmap.yaml

# Confirm the new plan is loaded
kubectl exec $POD -- curl -s http://localhost:8080/actuator/chaos | jq .scenarios
```

## Viewing Virtual Thread Pinning Metrics (Module 2)

```bash
POD=$(kubectl get pods -l app=sb4-impossible -o jsonpath='{.items[0].metadata.name}')

# Raw Prometheus metrics
kubectl exec $POD -- curl -s http://localhost:8080/actuator/prometheus \
  | grep chaos_virtual_thread_pinning

# Watch the HPA react
kubectl get hpa sb4-impossible-vthread-pinning-scaler -w
```
