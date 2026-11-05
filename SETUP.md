# CI/CD Setup: Jenkins + GitHub Webhook → Maven → Docker → Amazon ECR → Ansible → Amazon EKS

Work through the phases in order. Each phase ends with a **Checkpoint**: don't start the next
phase until the checkpoint passes. Region used throughout: **ap-south-1 (Mumbai)**.

## Architecture

```
 Developer ──git push──▶ GitHub ──webhook──▶ Jenkins (EC2, IAM role)
                                               │
                     ┌─────────────────────────┼──────────────────────────────┐
                     ▼                         ▼                              ▼
             Maven build + 14 tests     docker build + push            Ansible deploy.yml
             (fail → pipeline stops)    ──▶ Amazon ECR                 ──▶ Amazon EKS
                                            (tag = commit SHA)             Deployment + LoadBalancer
                                                                           smoke test /api/info
                                                                                │
                                                          Browser ◀── http://<elb-hostname>/
```

| Requirement           | Tool                                    | File                          |
|-----------------------|-----------------------------------------|-------------------------------|
| Source control        | Git + GitHub                            | this repo                     |
| Trigger on push       | GitHub webhook → Jenkins                | `Jenkinsfile` (`githubPush()`)|
| Build & test          | Maven, JUnit 5                          | `pom.xml`, `src/test`         |
| Containerization      | Docker                                  | `Dockerfile`                  |
| Registry              | Amazon ECR                              | `Jenkinsfile`                 |
| Orchestration         | Kubernetes on Amazon EKS                | `eks/cluster.yaml`, `k8s/*.yaml` |
| Configuration mgmt    | Ansible: sets up the Jenkins server     | `ansible/jenkins-setup.yml`   |
| Deployment automation | Ansible: deploys to EKS + smoke test    | `ansible/deploy.yml`          |
| CI/CD                 | Jenkins declarative pipeline            | `Jenkinsfile`                 |

---

## Phase 0: Prepare the repo

Your repo still has the earlier GitHub Actions workflow, which will fail on every push.

```bash
git rm -r .github
```

Copy in the new files from this zip (`Jenkinsfile`, `eks/`, `ansible/jenkins-setup.yml`,
updated `ansible/deploy.yml`, `SETUP.md`, `README.md`), then:

```bash
git add .
git commit -m "Switch pipeline to Jenkins + AWS EKS"
git push
```

Keep the repo **public**. A private repo works too, but Jenkins then needs a GitHub token
credential, which isn't covered here.

**Checkpoint:** GitHub shows `Jenkinsfile`, `ansible/`, `eks/` and no `.github/` folder.

---

## Phase 1: AWS account preparation

1. Sign in to the AWS console and switch the region (top right) to **Asia Pacific (Mumbai) ap-south-1**.
2. **Billing → Budgets → Create budget → Monthly cost budget**, e.g. $15, with your email. This
   warns you if something is left running.
3. Open **CloudShell** (the `>_` icon in the top bar) and run:

```bash
REGION=ap-south-1

# IAM role the Jenkins server will use (no access keys needed anywhere)
aws iam create-role --role-name jenkins-ec2-role \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}'

# Push/pull images to ECR
aws iam attach-role-policy --role-name jenkins-ec2-role \
  --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPowerUser

# Read cluster details (needed to build the kubeconfig)
aws iam put-role-policy --role-name jenkins-ec2-role --policy-name eks-describe \
  --policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"eks:DescribeCluster","Resource":"*"}]}'

# Wrap the role so it can be attached to an EC2 instance
aws iam create-instance-profile --instance-profile-name jenkins-ec2-role
aws iam add-role-to-instance-profile --instance-profile-name jenkins-ec2-role --role-name jenkins-ec2-role

# Image registry
aws ecr create-repository --repository-name task-manager --region $REGION
```

**Checkpoint:**
```bash
aws iam get-instance-profile --instance-profile-name jenkins-ec2-role --query 'InstanceProfile.Roles[0].RoleName'
aws ecr describe-repositories --region ap-south-1 --query 'repositories[].repositoryUri'
```
The first command prints `"jenkins-ec2-role"` and the second prints your repository URI.

---

## Phase 2: Jenkins server on EC2 (installed by Ansible)

### 2a. Launch the instance (EC2 console → Launch instance)

| Setting            | Value                                                       |
|--------------------|-------------------------------------------------------------|
| Name               | `jenkins-server`                                            |
| AMI                | Ubuntu Server 24.04 LTS, 64-bit (x86)                       |
| Instance type      | `t3.medium` (Jenkins + Maven + Docker need the 4 GB RAM)    |
| Key pair           | Create new → `jenkins-key` → download the `.pem`            |
| Network            | Default VPC, **Auto-assign public IP: Enable**              |
| Security group     | New `jenkins-sg`: SSH (22) from **My IP**; Custom TCP **8080** from **Anywhere 0.0.0.0/0** (GitHub must reach the webhook) |
| Storage            | 30 GiB gp3                                                  |
| Advanced details → IAM instance profile | `jenkins-ec2-role`                     |

### 2b. Give it a fixed IP

**EC2 → Elastic IPs → Allocate → Associate** with `jenkins-server`. Without this, the public IP
changes whenever the instance is stopped, which breaks the webhook URL.

### 2c. Connect

From Windows PowerShell, in the folder containing the key:

```powershell
icacls jenkins-key.pem /inheritance:r /grant:r "$($env:USERNAME):(R)"
ssh -i jenkins-key.pem ubuntu@<ELASTIC-IP>
```

### 2d. Run the Ansible setup playbook on the server

```bash
sudo apt update && sudo apt install -y ansible git
git clone https://github.com/<your-username>/task-manager.git
cd task-manager
sudo ansible-playbook -i ansible/inventory.ini ansible/jenkins-setup.yml
```

It takes about 5 minutes. The last task prints Jenkins' **first-login password**; copy it.

**Checkpoint** (run on the server):
```bash
sudo -u jenkins aws sts get-caller-identity   # Arn contains assumed-role/jenkins-ec2-role
sudo -u jenkins docker ps                     # empty table, no "permission denied"
```
And `http://<ELASTIC-IP>:8080` shows the Jenkins unlock page in your browser.

---

## Phase 3: Jenkins job and first CI run (no Kubernetes yet)

### 3a. First-time Jenkins setup
1. Open `http://<ELASTIC-IP>:8080`, paste the first-login password.
2. **Install suggested plugins** (includes Git, GitHub, Pipeline, JUnit, Timestamper).
3. Create your admin user. Keep the Jenkins URL as `http://<ELASTIC-IP>:8080/`.

### 3b. Create the pipeline job
1. **New Item** → name `task-manager` → **Pipeline** → OK.
2. **General**: tick **GitHub project**, URL `https://github.com/<you>/task-manager/`.
3. **Triggers**: tick **GitHub hook trigger for GITScm polling**.
4. **Pipeline**:
   - Definition: **Pipeline script from SCM**
   - SCM: **Git**, Repository URL `https://github.com/<you>/task-manager.git`
   - Branch: `*/main`
   - Script Path: `Jenkinsfile`
5. Save → **Build Now**.

**Expected result:** Prepare, Build & Test, Docker Build and Push to ECR pass. **Deploy to EKS
fails**, because the cluster doesn't exist yet. That's fine; CI is proven working.

**Checkpoint:**
- The build page shows **Test Result: 14 tests, 0 failures**.
- ECR console → `task-manager` repository shows an image tagged with your 7-character commit SHA.

---

## Phase 4: EKS cluster

Run in **CloudShell** (it uses your console identity, which has the permissions to create clusters).

```bash
# Install eksctl
curl -sLO "https://github.com/eksctl-io/eksctl/releases/latest/download/eksctl_Linux_amd64.tar.gz"
tar -xzf eksctl_Linux_amd64.tar.gz -C /tmp && sudo mv /tmp/eksctl /usr/local/bin/
eksctl version

# Get the cluster config from your repo
git clone https://github.com/<your-username>/task-manager.git
cd task-manager

# Create the cluster (15-20 minutes)
eksctl create cluster -f eks/cluster.yaml
```

> If CloudShell disconnects while waiting, the cluster keeps being created in the background.
> Reopen CloudShell and run `eksctl get cluster --region ap-south-1`, then
> `aws eks update-kubeconfig --name task-manager-cluster --region ap-south-1`.

Then allow the Jenkins role to deploy to the cluster:

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
JENKINS_ROLE=arn:aws:iam::$ACCOUNT_ID:role/jenkins-ec2-role

aws eks create-access-entry --cluster-name task-manager-cluster --region ap-south-1 \
  --principal-arn $JENKINS_ROLE

aws eks associate-access-policy --cluster-name task-manager-cluster --region ap-south-1 \
  --principal-arn $JENKINS_ROLE \
  --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy \
  --access-scope type=cluster
```

**Checkpoint:**
- In CloudShell: `kubectl get nodes` shows 1 node with STATUS `Ready`.
- On the Jenkins server (SSH):
  ```bash
  sudo -u jenkins aws eks update-kubeconfig --name task-manager-cluster --region ap-south-1 --kubeconfig /tmp/kc
  sudo -u jenkins kubectl --kubeconfig /tmp/kc get nodes
  ```
  shows the same node. If you get `Unauthorized`, the access entry step didn't apply.

---

## Phase 5: First full deployment

In Jenkins → `task-manager` → **Build Now**.

All 5 stages should go green. The first deploy takes longer (≈ 5 min) because AWS is creating the
load balancer and its DNS name. The end of the console log shows:

```
"msg": "Version a1b2c3d is live at http://a1b2c3...ap-south-1.elb.amazonaws.com"
```

**Checkpoint:** that URL opens the Task Manager in your browser, and the top-right shows
`version: <commit SHA>`. You can add and complete tasks.

You can also see it with (CloudShell):
```bash
kubectl get pods,svc -n task-manager
```

---

## Phase 6: GitHub webhook (automatic trigger on every push)

1. GitHub repo → **Settings → Webhooks → Add webhook**
   - Payload URL: `http://<ELASTIC-IP>:8080/github-webhook/` (the trailing `/` matters)
   - Content type: `application/json`
   - Events: **Just the push event**
   - Active: ticked → **Add webhook**
2. GitHub sends a test "ping".

**Checkpoint:**
- Webhook → **Recent Deliveries** shows the ping with a green tick (HTTP 200).
- Make any small commit and push. A new Jenkins build starts within a few seconds, and its
  page says **"Started by GitHub push by <your-username>"**.

---

## Phase 7: Demonstrations for grading

### Demo A: a code change reaches the live app automatically
1. Note the version in the top-right of the live page.
2. In `src/main/resources/static/index.html`, change `<h1>Task Manager</h1>` to `<h1>Task Manager v2</h1>`.
3. `git commit -am "Change heading" && git push`
4. Watch Jenkins run all stages automatically (no clicking).
5. Refresh the browser: new heading, and the version equals `git log -1 --format=%h`.

### Demo B: a failing test blocks deployment
1. In `TaskServiceTest.deletedIdIsNeverReused`, change `isEqualTo(2L)` to `isEqualTo(1L)`.
2. Commit and push.
3. Jenkins fails at **3. Test (JUnit)**; the Docker, ECR, Deploy and Verify stages are skipped; the live site still
   shows the previous version. The Test Result page names the failing test.
4. Revert and push to recover.

**Screenshots to capture:** webhook Recent Deliveries, Jenkins Stage View (all green), Stage View
for the failed run, test results page, ECR image list with SHA tags, `kubectl get pods,svc -n task-manager`,
and the browser before and after Demo A.

---

## Phase 8: Clean up (stops the charges)

In CloudShell:
```bash
cd task-manager
kubectl delete namespace task-manager        # removes the load balancer first
eksctl delete cluster -f eks/cluster.yaml    # ~10 minutes
aws ecr delete-repository --repository-name task-manager --region ap-south-1 --force
```

In the EC2 console: **terminate** `jenkins-server`, then **Elastic IPs → Release** the address
(an unattached Elastic IP is still billed).

**Checkpoint:** EC2, EKS, ECR and Load Balancers consoles show nothing left in ap-south-1.

---

## Design notes for the report

- **No stored cloud credentials:** Jenkins gets AWS access from the EC2 instance's IAM role;
  there are no access keys in Jenkins or Git.
- **Build once, deploy the same artifact:** the Docker image packages the JAR that just passed
  the tests; it is never rebuilt from source.
- **Traceable images:** each image is tagged with the commit SHA, which the app shows at `/api/info`.
- **Zero-downtime rollouts:** `maxUnavailable: 0` and readiness probes keep the old pod serving
  until the new one is healthy. If the new pod never becomes ready, Ansible's wait fails the
  build and the old version stays live.
- **Verified deploys:** the pipeline only succeeds once the live URL reports the new version.
- **Ansible in two roles:** configuration management (the Jenkins server's whole toolchain is
  defined in `jenkins-setup.yml`, so it can be rebuilt identically) and deployment automation
  (`deploy.yml` fills in the image tag in `k8s/*.yaml` and applies them; `verify.yml` checks the rollout and the live URL).
- **Known limitations:** tasks are stored in memory, so the app runs as 1 replica and data resets
  on each deploy; scaling out needs a shared database (e.g. Amazon RDS). Port 8080 is open to the
  internet for webhooks; production would restrict it to GitHub's webhook IP ranges and use HTTPS.
