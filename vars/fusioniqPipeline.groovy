def call(Map config = [:]) {
  pipeline {
    agent any

    options {
      timeout(time: 30, unit: 'MINUTES')
      disableConcurrentBuilds()
      timestamps()
    }

    parameters {
      booleanParam(name: 'SKIP_QUALITY_CHECKS', defaultValue: false, description: 'Skip OWASP & Sonar quality stages')
    }

    tools {
      maven 'maven-3.9.8'
    }

    environment {
      APP_NAME        = "fusioniq-api-gateway"
      APP_VERSION     = ""
      DOCKER_IMAGE    = ""
      AWS_REGION      = "ap-south-1"
      SONAR_URL       = "https://sonarqube-logistics.surnoi.in:9000"
      SONAR_PROJECT   = "fusioniq-api-gateway"
      SONAR_CREDS     = "sonar-creds"
      ECR_URI         = "${config.ecrUri}"
      GIT_CREDS       = "${config.gitCreds}"
      REPO_URL        = "${config.repoUrl}"
      BRANCH_NAME     = "${config.branch ?: 'main'}"   // ✅ Default to main if not provided
      TEAMS_WEBHOOK   = credentials('teams-webhook')
    }

    stages {

      stage('Checkout') {
        steps {
          script {
            echo "🔹 Checking out source code from ${REPO_URL} (branch: ${BRANCH_NAME})"
            git branch: BRANCH_NAME, credentialsId: GIT_CREDS, url: REPO_URL
          }
        }
      }

      stage('Build and Package') {
        steps {
          script {
            echo "⚙️ Running Maven clean package"
            sh 'mvn clean package -DskipTests=true'
          }
        }
      }

      stage('SonarQube Analysis') {
        when { expression { return !params.SKIP_QUALITY_CHECKS } }
        steps {
          withSonarQubeEnv('SonarQubeServer') {
            script {
              echo "🔍 Running SonarQube analysis..."
              sh """
                mvn sonar:sonar \
                  -Dsonar.projectKey=${SONAR_PROJECT} \
                  -Dsonar.host.url=${SONAR_URL} \
                  -Dsonar.login=$SONAR_AUTH_TOKEN
              """
            }
          }
        }
      }

      stage('OWASP Dependency Check') {
        when { expression { return !params.SKIP_QUALITY_CHECKS } }
        steps {
          script {
            echo "🛡️ Running OWASP Dependency Check..."
            sh 'mvn org.owasp:dependency-check-maven:check'
          }
        }
      }

      stage('Docker Build') {
        steps {
          script {
            APP_VERSION = sh(script: "mvn help:evaluate -Dexpression=project.version -q -DforceStdout", returnStdout: true).trim()
            DOCKER_IMAGE = "${ECR_URI}:${APP_VERSION}"
            echo "🐳 Building Docker image: ${DOCKER_IMAGE}"
            sh "docker build -t ${DOCKER_IMAGE} ."
          }
        }
      }

      stage('Push to ECR') {
        steps {
          script {
            echo "🚀 Pushing Docker image to ECR..."
            sh """
              aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${ECR_URI}
              docker push ${DOCKER_IMAGE}
            """
          }
        }
      }

      stage('Deploy to EKS') {
        steps {
          script {
            echo "☸️ Deploying to Amazon EKS..."
            sh """
              kubectl set image deployment/${APP_NAME}-deployment ${APP_NAME}-container=${DOCKER_IMAGE} -n fusioniq || true
              kubectl rollout restart deployment/${APP_NAME}-deployment -n fusioniq
            """
          }
        }
      }
    }

    post {
      success {
        script {
          echo "✅ Build succeeded!"
          sendTeamsNotification("SUCCESS", env.JOB_NAME, env.BUILD_NUMBER, env.BUILD_URL)
        }
      }

      failure {
        script {
          echo "❌ Build failed!"
          sendTeamsNotification("FAILURE", env.JOB_NAME, env.BUILD_NUMBER, env.BUILD_URL)
        }
      }

      always {
        cleanWs()
      }
    }
  }
}

/**
 * Sends a Microsoft Teams notification using the JSON template.
 */
def sendTeamsNotification(String status, String jobName, String buildNumber, String buildUrl) {
  def payloadTemplate = libraryResource('teams_payload_template.json')
  def payload = payloadTemplate
    .replace('${BUILD_STATUS}', status)
    .replace('${JOB_NAME}', jobName)
    .replace('${BUILD_NUMBER}', buildNumber)
    .replace('${BUILD_URL}', buildUrl)
    .replace('${BUILD_USER}', "${env.BUILD_USER ?: 'Jenkins'}")

  httpRequest(
    httpMode: 'POST',
    contentType: 'APPLICATION_JSON',
    requestBody: payload,
    url: TEAMS_WEBHOOK
  )
}
