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
      // 🔹 Just paste your full pipeline stages here as they are
      // (the same ones from your Jenkinsfile)
      // Everything inside "stages { ... }" can remain unchanged
    }

    post {
      // same post block (success/failure notifications, etc.)
    }
  }
}
