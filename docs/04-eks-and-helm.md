# HMS Part 4: EKS Cluster & Helm Deployment

Runs the gateway and the six services on EKS inside the VPC from Part 3. Untested reference: validate with `terraform plan` / `helm lint` / `helm template` before applying.

```
infra/eks.tf                 EKS, node groups, IRSA, add-ons
deploy/charts/hms-service/   one generic chart reused by every service
deploy/values/<service>.yaml per-service overrides
deploy/k8s/                  namespace, network policies, Strimzi Kafka Connect (Debezium)
```

---

## 1. eks.tf

```hcl
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.24"

  cluster_name    = "hms"
  cluster_version = "1.30"

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  cluster_endpoint_public_access  = false          # API reachable only from the VPC / VPN
  cluster_endpoint_private_access = true
  enable_irsa                     = true

  cluster_encryption_config = { resources = ["secrets"] }   # envelope encryption (KMS key created by module)

  cluster_enabled_log_types = ["api", "audit", "authenticator"]

  cluster_addons = {
    coredns                = {}
    kube-proxy             = {}
    vpc-cni                = { configuration_values = jsonencode({ enableNetworkPolicy = "true" }) }
    eks-pod-identity-agent = {}
    aws-ebs-csi-driver     = { service_account_role_arn = module.ebs_csi_irsa.iam_role_arn }
  }

  eks_managed_node_groups = {
    apps = {
      instance_types = ["m6i.xlarge"]
      min_size       = 3
      max_size       = 12
      desired_size   = 3
      ami_type       = "AL2023_x86_64_STANDARD"
      labels         = { workload = "apps" }
      metadata_options = { http_tokens = "required", http_put_response_hop_limit = 1 }   # IMDSv2 only
    }
  }

  # Let the ALBs from Part 3 reach pods (target_type = "ip")
  node_security_group_additional_rules = {
    from_gateway_alb = {
      type                     = "ingress"
      protocol                 = "tcp"
      from_port                = 8080
      to_port                  = 8080
      source_security_group_id = aws_security_group.external_alb.id
    }
    from_internal_alb = {
      type                     = "ingress"
      protocol                 = "tcp"
      from_port                = 8080
      to_port                  = 8080
      source_security_group_id = aws_security_group.internal_alb.id
    }
  }
}

module "ebs_csi_irsa" {
  source    = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version   = "~> 5.44"
  role_name = "hms-ebs-csi"
  attach_ebs_csi_policy = true
  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:ebs-csi-controller-sa"]
    }
  }
}

# AWS Load Balancer Controller: needed for TargetGroupBinding (registers pod IPs in the Part 3 target groups)
module "lbc_irsa" {
  source    = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version   = "~> 5.44"
  role_name = "hms-aws-lbc"
  attach_load_balancer_controller_policy                          = true
  attach_load_balancer_controller_targetgroup_binding_only_policy = true
  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:aws-load-balancer-controller"]
    }
  }
}

# Per-service IRSA roles (least privilege), e.g. notification-service may call SES + SNS only
module "irsa_notification" {
  source    = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version   = "~> 5.44"
  role_name = "hms-notification"
  role_policy_arns = { ses_sns = aws_iam_policy.notification.arn }
  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["hms:notification-service"]
    }
  }
}

resource "aws_iam_policy" "notification" {
  name = "hms-notification-send"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = ["ses:SendEmail", "sns:Publish"], Resource = "*" }]
  })
}
```

Install cluster add-ons (Helm, once per cluster): `aws-load-balancer-controller`, `external-secrets` (pull from Secrets Manager), `metrics-server`, `strimzi-kafka-operator`, an OpenTelemetry collector, and optionally Karpenter instead of managed node groups for autoscaling.

---

## 2. Namespace and baseline policy

```yaml
# deploy/k8s/namespace.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: hms
  labels:
    pod-security.kubernetes.io/enforce: restricted
---
# Default deny, then allow explicitly
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: default-deny, namespace: hms }
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: allow-dns, namespace: hms }
spec:
  podSelector: {}
  policyTypes: [Egress]
  egress:
    - to:
        - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: kube-system } }
      ports: [{ protocol: UDP, port: 53 }, { protocol: TCP, port: 53 }]
```

Per-service allow rules come from the chart (section 3), so each service declares who may call it.

---

## 3. Generic Helm chart: `hms-service`

### Chart.yaml

```yaml
apiVersion: v2
name: hms-service
version: 1.0.0
appVersion: "1.0.0"
```

### values.yaml (defaults)

```yaml
image:
  repository: ""            # ECR repo
  tag: ""                   # set by CI (immutable digest preferred)
replicaCount: 3
port: 8080
serviceAccount:
  create: true
  roleArn: ""               # IRSA role, optional
env: {}                     # plain env vars
externalSecret:
  enabled: true
  secretStore: aws-secrets-manager
  keys: []                  # [{ secretKey: DB_PASSWORD, remoteRef: hms/patient/db, property: password }]
resources:
  requests: { cpu: 500m, memory: 768Mi }
  limits:   { memory: 1Gi }
jvm:
  opts: "-XX:MaxRAMPercentage=70 -XX:+UseZGC -XX:+ZGenerational -Djava.security.egd=file:/dev/urandom"
autoscaling:
  enabled: true
  minReplicas: 3
  maxReplicas: 10
  cpuTarget: 65
pdb:
  minAvailable: 2
targetGroup:
  arn: ""                   # Part 3 target group ARN; empty = no TargetGroupBinding
network:
  allowFrom: []             # list of app names allowed to call this service
  egressToDataCidrs: []     # e.g. data subnet CIDRs
```

### templates/deployment.yaml

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}
  labels: { app: {{ .Release.Name }} }
spec:
  replicas: {{ .Values.replicaCount }}
  strategy:
    type: RollingUpdate
    rollingUpdate: { maxSurge: 1, maxUnavailable: 0 }
  selector:
    matchLabels: { app: {{ .Release.Name }} }
  template:
    metadata:
      labels: { app: {{ .Release.Name }} }
      annotations:
        checksum/env: {{ toYaml .Values.env | sha256sum }}
    spec:
      serviceAccountName: {{ .Release.Name }}
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        seccompProfile: { type: RuntimeDefault }
      terminationGracePeriodSeconds: 45
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
          labelSelector: { matchLabels: { app: {{ .Release.Name }} } }
      containers:
        - name: app
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          ports: [{ name: http, containerPort: {{ .Values.port }} }]
          env:
            - { name: JAVA_TOOL_OPTIONS, value: {{ .Values.jvm.opts | quote }} }
            - { name: SERVER_SHUTDOWN, value: graceful }
            {{- range $k, $v := .Values.env }}
            - { name: {{ $k }}, value: {{ $v | quote }} }
            {{- end }}
          {{- if .Values.externalSecret.enabled }}
          envFrom:
            - secretRef: { name: {{ .Release.Name }}-secrets }
          {{- end }}
          resources: {{ toYaml .Values.resources | nindent 12 }}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: { drop: ["ALL"] }
          volumeMounts: [{ name: tmp, mountPath: /tmp }]
          startupProbe:
            httpGet: { path: /actuator/health/liveness, port: http }
            failureThreshold: 30
            periodSeconds: 5
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: http }
            periodSeconds: 10
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: http }
            periodSeconds: 5
          lifecycle:
            preStop:
              exec: { command: ["sleep", "10"] }   # let the ALB deregister before SIGTERM
      volumes: [{ name: tmp, emptyDir: {} }]
```

Matching Spring properties for graceful shutdown and probes:

```yaml
server.shutdown: graceful
spring.lifecycle.timeout-per-shutdown-phase: 25s
management.endpoint.health.probes.enabled: true
management.health.readinessstate.enabled: true
```

### templates/service-sa-hpa-pdb.yaml

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ .Release.Name }}
  {{- if .Values.serviceAccount.roleArn }}
  annotations: { eks.amazonaws.com/role-arn: {{ .Values.serviceAccount.roleArn }} }
  {{- end }}
---
apiVersion: v1
kind: Service
metadata: { name: {{ .Release.Name }} }
spec:
  selector: { app: {{ .Release.Name }} }
  ports: [{ name: http, port: 80, targetPort: http }]
---
{{- if .Values.autoscaling.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: {{ .Release.Name }} }
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: {{ .Release.Name }} }
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: {{ .Values.autoscaling.cpuTarget }} } }
{{- end }}
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: {{ .Release.Name }} }
spec:
  minAvailable: {{ .Values.pdb.minAvailable }}
  selector: { matchLabels: { app: {{ .Release.Name }} } }
```

### templates/externalsecret.yaml

```yaml
{{- if .Values.externalSecret.enabled }}
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata: { name: {{ .Release.Name }}-secrets }
spec:
  refreshInterval: 1h
  secretStoreRef: { name: {{ .Values.externalSecret.secretStore }}, kind: ClusterSecretStore }
  target: { name: {{ .Release.Name }}-secrets }
  data:
    {{- range .Values.externalSecret.keys }}
    - secretKey: {{ .secretKey }}
      remoteRef: { key: {{ .remoteRef }}, property: {{ .property }} }
    {{- end }}
{{- end }}
```

### templates/targetgroupbinding.yaml (ties pods to the Part 3 ALB target groups)

```yaml
{{- if .Values.targetGroup.arn }}
apiVersion: elbv2.k8s.aws/v1beta1
kind: TargetGroupBinding
metadata: { name: {{ .Release.Name }} }
spec:
  serviceRef: { name: {{ .Release.Name }}, port: 80 }
  targetGroupARN: {{ .Values.targetGroup.arn }}
  targetType: ip
{{- end }}
```

### templates/networkpolicy.yaml

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: {{ .Release.Name }} }
spec:
  podSelector: { matchLabels: { app: {{ .Release.Name }} } }
  policyTypes: [Ingress, Egress]
  ingress:
    {{- if .Values.targetGroup.arn }}
    - from:
        - ipBlock: { cidr: 10.20.0.0/16 }           # ALB ENIs live in the VPC
      ports: [{ port: {{ .Values.port }} }]
    {{- end }}
    {{- range .Values.network.allowFrom }}
    - from: [{ podSelector: { matchLabels: { app: {{ . }} } } }]
      ports: [{ port: {{ $.Values.port }} }]
    {{- end }}
    - from: [{ namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: monitoring } } }]
      ports: [{ port: {{ .Values.port }} }]
  egress:
    {{- range .Values.network.egressToDataCidrs }}
    - to: [{ ipBlock: { cidr: {{ . }} } }]
    {{- end }}
    - to: [{ ipBlock: { cidr: 0.0.0.0/0 } }]       # AWS APIs via NAT; narrow with VPC endpoints later
      ports: [{ port: 443 }]
```

A pod-IP-level `ipBlock` for the whole VPC is coarse. Tighten it to the ALB subnets' CIDRs once settled.

---

## 4. Per-service values

```yaml
# deploy/values/appointment-service.yaml
image: { repository: 123456789012.dkr.ecr.eu-west-1.amazonaws.com/hms/appointment-service }
env:
  SPRING_PROFILES_ACTIVE: prod
  SPRING_DATASOURCE_URL: jdbc:postgresql://appointments.cluster-xyz.eu-west-1.rds.amazonaws.com:5432/appointments?sslmode=verify-full
  SPRING_KAFKA_BOOTSTRAP_SERVERS: b-1.hms.kafka.eu-west-1.amazonaws.com:9098   # MSK IAM listener; SASL settings live in hms-common application.yml (Part 5, section 3.3)
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: https://auth.hms.example.com/realms/hms
externalSecret:
  keys:
    - { secretKey: SPRING_DATASOURCE_USERNAME, remoteRef: hms/appointment/db, property: username }
    - { secretKey: SPRING_DATASOURCE_PASSWORD, remoteRef: hms/appointment/db, property: password }
targetGroup:
  arn: arn:aws:elasticloadbalancing:eu-west-1:123456789012:targetgroup/hms-appointment/abc123
network:
  allowFrom: [api-gateway]
  egressToDataCidrs: [10.20.20.0/22]
```

```yaml
# deploy/values/api-gateway.yaml
image: { repository: 123456789012.dkr.ecr.eu-west-1.amazonaws.com/hms/api-gateway }
resources:
  requests: { cpu: "1", memory: 1Gi }
  limits:   { memory: 1536Mi }
env:
  SPRING_DATA_REDIS_HOST: hms-redis.xyz.cache.amazonaws.com
  SPRING_CLOUD_KUBERNETES_DISCOVERY_ENABLED: "true"
targetGroup:
  arn: arn:aws:elasticloadbalancing:eu-west-1:123456789012:targetgroup/hms-gateway/def456   # external ALB target group
network:
  egressToDataCidrs: [10.20.20.0/22]
```

```yaml
# deploy/values/notification-service.yaml
image: { repository: 123456789012.dkr.ecr.eu-west-1.amazonaws.com/hms/notification-service }
serviceAccount: { roleArn: arn:aws:iam::123456789012:role/hms-notification }
autoscaling: { minReplicas: 2, maxReplicas: 6 }
pdb: { minAvailable: 1 }
# no targetGroup: Kafka consumer only
```

Deploy: `helm upgrade --install appointment-service deploy/charts/hms-service -n hms -f deploy/values/appointment-service.yaml --set image.tag=$GIT_SHA`

Kubernetes-native discovery (from Part 2) needs RBAC for the gateway:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: { name: discovery-reader, namespace: hms }
rules:
  - apiGroups: [""]
    resources: [services, endpoints, pods]
    verbs: [get, list, watch]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: { name: gateway-discovery, namespace: hms }
roleRef: { apiGroup: rbac.authorization.k8s.io, kind: Role, name: discovery-reader }
subjects: [{ kind: ServiceAccount, name: api-gateway, namespace: hms }]
```

---

## 5. Debezium on Strimzi (Kafka Connect against MSK)

```yaml
# deploy/k8s/kafka-connect.yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaConnect
metadata:
  name: hms-connect
  namespace: hms
  annotations: { strimzi.io/use-connector-resources: "true" }
spec:
  version: 3.8.0
  replicas: 2
  template:
    serviceAccount:
      metadata:
        annotations:
          eks.amazonaws.com/role-arn: arn:aws:iam::123456789012:role/hms-kafka-connect   # IRSA: kafka-cluster:* on hms.*.events
  bootstrapServers: b-1.hms.kafka.eu-west-1.amazonaws.com:9098
  tls: { trustedCertificates: [] }                 # MSK uses public ACM certs
  config:
    group.id: hms-connect
    offset.storage.topic: connect-offsets
    config.storage.topic: connect-configs
    status.storage.topic: connect-status
    config.storage.replication.factor: 3
    offset.storage.replication.factor: 3
    status.storage.replication.factor: 3
    config.providers: secrets
    config.providers.secrets.class: io.strimzi.kafka.KubernetesSecretConfigProvider
    # MSK IAM authentication (worker, producer, consumer, admin all need it)
    security.protocol: SASL_SSL
    sasl.mechanism: AWS_MSK_IAM
    sasl.jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;
    sasl.client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler
    producer.security.protocol: SASL_SSL
    producer.sasl.mechanism: AWS_MSK_IAM
    producer.sasl.jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;
    producer.sasl.client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler
    consumer.security.protocol: SASL_SSL
    consumer.sasl.mechanism: AWS_MSK_IAM
    consumer.sasl.jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;
    consumer.sasl.client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler
    admin.security.protocol: SASL_SSL
    admin.sasl.mechanism: AWS_MSK_IAM
    admin.sasl.jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;
    admin.sasl.client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler
  build:
    output:
      type: docker
      image: 123456789012.dkr.ecr.eu-west-1.amazonaws.com/hms/connect-debezium:2.7
    plugins:
      - name: debezium-postgres
        artifacts:
          - type: maven
            group: io.debezium
            artifact: debezium-connector-postgres
            version: 2.7.3.Final
      - name: msk-iam-auth
        artifacts:
          - type: maven
            group: software.amazon.msk
            artifact: aws-msk-iam-auth
            version: 2.2.0
---
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaConnector
metadata:
  name: appointment-outbox
  namespace: hms
  labels: { strimzi.io/cluster: hms-connect }
spec:
  class: io.debezium.connector.postgresql.PostgresConnector
  tasksMax: 1
  config:
    database.hostname: appointments.cluster-xyz.eu-west-1.rds.amazonaws.com
    database.port: 5432
    database.user: ${secrets:hms/debezium-appointment:username}
    database.password: ${secrets:hms/debezium-appointment:password}
    database.dbname: appointments
    topic.prefix: appointment-db
    plugin.name: pgoutput
    publication.name: dbz_outbox
    slot.name: appointment_outbox_slot
    table.include.list: public.outbox
    heartbeat.interval.ms: 10000
    transforms: outbox
    transforms.outbox.type: io.debezium.transforms.outbox.EventRouter
    transforms.outbox.table.field.event.key: aggregateid
    transforms.outbox.table.field.event.type: type
    transforms.outbox.table.field.event.payload: payload
    transforms.outbox.route.by.field: aggregatetype
    transforms.outbox.route.topic.replacement: "hms.${routedByValue}.events"
    transforms.outbox.table.expand.json.payload: true
    transforms.outbox.table.fields.additional.placement: "type:header:eventType,id:header:messageId"
    key.converter: org.apache.kafka.connect.storage.StringConverter
    value.converter: org.apache.kafka.connect.json.JsonConverter
    value.converter.schemas.enable: false
```

Create one `KafkaConnector` per service database (patient, appointment, billing, pharmacy, iam). On RDS, set `rds.logical_replication = 1` in the parameter group and grant the connector user `rds_replication`. The `${secrets:...}` syntax reads a Kubernetes Secret (`namespace/name:key`), so sync the Debezium credentials with ExternalSecrets into a Secret first and grant the Connect service account read access via a Role.

---

## 6. CI/CD sketch (GitHub Actions)

```yaml
name: deploy-appointment-service
on: { push: { branches: [main], paths: ["appointment-service/**", "hms-common/**"] } }
permissions: { id-token: write, contents: read }
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21, cache: maven }
      - run: mvn -pl appointment-service -am verify          # unit + Testcontainers tests
      - uses: aws-actions/configure-aws-credentials@v4
        with: { role-to-assume: arn:aws:iam::123456789012:role/gha-deploy, aws-region: eu-west-1 }
      - uses: aws-actions/amazon-ecr-login@v2
      - run: |
          mvn -pl appointment-service spring-boot:build-image \
            -Dspring-boot.build-image.imageName=$ECR/hms/appointment-service:${{ github.sha }}
          docker push $ECR/hms/appointment-service:${{ github.sha }}
      - run: |
          aws eks update-kubeconfig --name hms
          helm upgrade --install appointment-service deploy/charts/hms-service -n hms \
            -f deploy/values/appointment-service.yaml --set image.tag=${{ github.sha }} --atomic --timeout 5m
```

The cluster API is private, so the deploy job must run on a self-hosted runner inside the VPC (or use CodeBuild or an SSM tunnel). Scan images (ECR scanning or Trivy) and sign them (cosign) before deploy.

---

## 7. Rollout checklist

1. Apply `eks.tf`; install the add-ons; create the `ClusterSecretStore` for Secrets Manager.
2. Apply the namespace and default-deny policies, then deploy `iam-auth-service` and `api-gateway` first.
3. Deploy the domain services; confirm pods register as healthy targets in the Part 3 target groups.
4. Create the outbox publications and Debezium connectors, then run the failure drills from Part 2 (kill a pod mid-saga, drain a node, stop Connect and restart).
5. Load test and verify the HPA scales; confirm the PDB blocks a drain that would drop below the minimum.

Not included: Karpenter config, service mesh / mTLS (Linkerd or Istio), Prometheus/Grafana stack, and the data tier in Terraform (RDS, MSK, ElastiCache). Next options: the data tier in Terraform, or the ABAC policy test matrix.
