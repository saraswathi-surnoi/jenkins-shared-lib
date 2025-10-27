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

    tools { maven 'maven-3.9.8' }

    environment {
      APP_VERSION     = ""
      DOCKER_IMAGE    = ""
      CONTAINER_NAME  = ""
      AWS_REGION      = "ap-south-1"
      SONAR_PROJECT   = "fusioniq-api-gateway"
      SONAR_URL       = "https://sonarqube-logistics.surnoi.in:9000"
    }

    stages {

      stage('Checkout') {
        steps {
          git credentialsId: 'git-access', url: 'https://github.com/SurnoiTechnology/API-Gateway-Service-FusionIQ-BE.git'
        }
      }

      stage('Read pom.xml') {
        steps {
          script {
            echo "📖 Reading version and artifactId from pom.xml..."
            env.APP_VERSION = sh(script: "mvn help:evaluate -Dexpression=project.version -q -DforceStdout | grep -Ev '(^\\[|Download)' | tail -n1", returnStdout: true).trim()
            env.DOCKER_IMAGE = sh(script: "mvn help:evaluate -Dexpression=project.artifactId -q -DforceStdout | grep -Ev '(^\\[|Download)' | tail -n1", returnStdout: true).trim()
            env.CONTAINER_NAME = env.DOCKER_IMAGE

            if (!env.APP_VERSION || env.APP_VERSION == "null") { env.APP_VERSION = "0.0.1-SNAPSHOT" }
            if (!env.DOCKER_IMAGE || env.DOCKER_IMAGE == "null") { env.DOCKER_IMAGE = "api-gateway" }

            echo "✅ Project: ${env.DOCKER_IMAGE}  Version: ${env.APP_VERSION}"

            writeFile file: 'build_metadata.env', text: """APP_VERSION=${env.APP_VERSION}
DOCKER_IMAGE=${env.DOCKER_IMAGE}
CONTAINER_NAME=${env.CONTAINER_NAME}
"""
          }
        }
      }

      stage('Build (Maven)') {
        steps { sh 'mvn clean package -DskipTests -B' }
        post { success { archiveArtifacts artifacts: 'target/*.jar', allowEmptyArchive: true } }
      }

      stage('Quality Checks') {
        when { expression { !params.SKIP_QUALITY_CHECKS } }
        parallel {

          stage('Security Scan (OWASP)') {
            steps { sh 'mvn org.owasp:dependency-check-maven:check -Dformat=ALL -DoutputDirectory=target -B || true' }
            post {
              always {
                archiveArtifacts artifacts: 'target/dependency-check-report.*', allowEmptyArchive: true
                publishHTML(target: [reportDir: 'target', reportFiles: 'dependency-check-report.html', reportName: 'OWASP Dependency Report'])
              }
            }
          }

          stage('Sonar Scan') {
            environment { scannerHome = tool 'sonar-7.2' }
            steps {
              withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                withSonarQubeEnv('SonarQube-Server') {
                  sh """${scannerHome}/bin/sonar-scanner -Dsonar.login=$SONAR_TOKEN -Dproject.settings=sonar-project.properties"""
                }
              }
            }
          }
        }
      }

      stage('Quality Gate Check') {
        when { expression { !params.SKIP_QUALITY_CHECKS } }
        steps {
          script {
            retry(3) {
              timeout(time: 5, unit: 'MINUTES') {
                echo "⏳ Waiting for SonarQube Quality Gate result..."
                def qg = waitForQualityGate abortPipeline: true
                if (qg.status != 'OK') { error "❌ Quality Gate failed: ${qg.status}" }
                echo "✅ Quality Gate passed."
              }
            }
          }
        }
      }

      stage('Build Docker Image') {
        steps {
          script {
            def meta = readFile('build_metadata.env').split("\n").collectEntries { def p = it.split('='); [(p[0]): p[1]] }
            def appVer = meta['APP_VERSION']
            def imageName = meta['DOCKER_IMAGE']
            echo "🐳 Building Docker image: ${imageName}:${appVer}"
            sh "docker build -t ${imageName}:${appVer} ."
          }
        }
      }

      stage('Push Image to ECR') {
        steps {
          script {
            def meta = readFile('build_metadata.env').split("\n").collectEntries { def p = it.split('='); [(p[0]): p[1]] }
            def appVer = meta['APP_VERSION']
            def imageName = meta['DOCKER_IMAGE']
            def ECR_URI = "361769585646.dkr.ecr.ap-south-1.amazonaws.com/fusioniq/backend"

            echo "📦 Pushing Docker images to ECR: ${ECR_URI}"
            echo "🧩 Using tag: ${appVer}"

            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'aws-ecr-creds']]) {
              sh """
                aws ecr get-login-password --region ${env.AWS_REGION} | docker login --username AWS --password-stdin ${ECR_URI}

                docker tag ${imageName}:${appVer} ${ECR_URI}:${appVer}
                docker tag ${imageName}:${appVer} ${ECR_URI}:latest

                docker push ${ECR_URI}:${appVer}
                docker push ${ECR_URI}:latest

                docker logout ${ECR_URI}
              """
            }
          }
        }
      }

      stage('ECR Image Scan') {
        steps {
          script {
            def meta = readFile('build_metadata.env').split("\n").collectEntries { def p = it.split('='); [(p[0]): p[1]] }
            def appVer = meta['APP_VERSION']

            echo "🔍 Starting ECR scan for ${appVer}..."
            def startStatus = sh(script: "aws ecr start-image-scan --repository-name fusioniq/backend --image-id imageTag=${appVer} --region ${env.AWS_REGION}", returnStatus: true)

            if (startStatus != 0) { echo "⚠️ Failed to start ECR image scan (non-fatal)." }
            else {
              timeout(time: 10, unit: 'MINUTES') {
                waitUntil {
                  def s = sh(script: "aws ecr describe-image-scan-findings --repository-name fusioniq/backend --image-id imageTag=${appVer} --region ${env.AWS_REGION} --query 'imageScanStatus.status' --output text || echo PENDING", returnStdout: true).trim()
                  echo "ECR scan status: ${s}"
                  return s == 'COMPLETE'
                }
              }
              def critical = sh(script: "aws ecr describe-image-scan-findings --repository-name fusioniq/backend --image-id imageTag=${appVer} --region ${env.AWS_REGION} --query 'imageScanFindings.findingSeverityCounts.CRITICAL' --output text || echo 0", returnStdout: true).trim()
              echo "🔎 ECR critical vulnerabilities: ${critical}"
            }
          }
        }
      }
    }

    post {
      always {
        echo "📦 Build Summary:"
        echo "   Docker Image: ${env.DOCKER_IMAGE}"
        echo "   Version: ${env.APP_VERSION}"
        echo "   Container: ${env.CONTAINER_NAME}"
        echo "   Result: ${currentBuild.currentResult}"
      }

      success {
        echo "✅ Pipeline completed successfully for ${env.DOCKER_IMAGE}:${env.APP_VERSION}"
        script { sendTeamsNotification('SUCCESS') }
      }

      failure {
        echo "❌ Pipeline failed. Sending Teams notification..."
        script { sendTeamsNotification('FAILURE') }
      }
    }
  }
}

/**
 * Sends a Microsoft Teams notification.
 */
def sendTeamsNotification(String status) {
  def SONAR_URL = "https://sonarqube-logistics.surnoi.in/dashboard?id=fusioniq-api-gateway"
  def ECR_URI = "361769585646.dkr.ecr.ap-south-1.amazonaws.com/fusioniq/backend"
  def ECR_LINK = "https://${ECR_URI.replace('.dkr.ecr', '.console.aws.amazon.com/ecr/repositories')}/?region=ap-south-1"
  def color = (status == 'SUCCESS') ? '2EB886' : 'E81123'
  def title = (status == 'SUCCESS') ? '✅ Jenkins Build Success' : '❌ Jenkins Build Failed'
  def summary = (status == 'SUCCESS') ? '✅ SUCCESS' : '❌ FAILURE'

  withCredentials([string(credentialsId: 'teams-webhook', variable: 'TEAMS_WEBHOOK')]) {
    writeFile file: 'teams_payload.json', text: """
    {
      "@type": "MessageCard",
      "@context": "https://schema.org/extensions",
      "summary": "${summary}: ${env.DOCKER_IMAGE} Build #${BUILD_NUMBER}",
      "themeColor": "${color}",
      "title": "${title} - ${env.DOCKER_IMAGE}",
      "sections": [{
        "facts": [
          { "name": "Version", "value": "${env.APP_VERSION}" },
          { "name": "Result", "value": "${currentBuild.currentResult}" }
        ],
        "markdown": true
      }],
      "potentialAction": [
        {
          "@type": "OpenUri",
          "name": "🔍 View Jenkins Build",
          "targets": [{ "os": "default", "uri": "${env.BUILD_URL}" }]
        },
        {
          "@type": "OpenUri",
          "name": "📊 View SonarQube Dashboard",
          "targets": [{ "os": "default", "uri": "${SONAR_URL}" }]
        },
        {
          "@type": "OpenUri",
          "name": "🐳 View ECR Repository",
          "targets": [{ "os": "default", "uri": "${ECR_LINK}" }]
        }
      ]
    }
    """
    sh """
      curl -s -o /dev/null -w "%{http_code}" \
        -H "Content-Type: application/json" \
        -d @teams_payload.json "$TEAMS_WEBHOOK"
    """
    echo "📨 Teams notification sent for ${status}."
  }
}
