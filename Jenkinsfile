pipeline {
  agent any

  options {
    timestamps()
    ansiColor('xterm')
    skipDefaultCheckout(false)
  }

  environment {
    // 레지스트리 호스트와 리포지토리 네임스페이스를 분리해 관리합니다.
    // 로그인은 호스트로만 수행하고, 태깅은 호스트/네임스페이스/이미지로 구성합니다.
    REGISTRY_HOST = 'docker.io'
    REPO_PREFIX  = 'minsik023'
    IMAGE_BACKEND = 'weather-backend'
    IMAGE_FRONTEND = 'weather-frontend'
    GIT_SHA = "${env.GIT_COMMIT ?: env.BUILD_TAG}"
    NODE_OPTIONS = '--no-warnings'
  }

  tools {
    maven 'Maven-3'
  }

  stages {
    stage('Checkout') {
      steps {
        checkout scm
        script {
          currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.BRANCH_NAME}@${env.GIT_COMMIT?.take(7)}"
        }
      }
    }

    stage('Backend Build & Test') {
      steps {
        dir('backend') {
          bat label: 'Maven Test', script: 'mvn -B -U -DskipTests=false test'
          bat label: 'Maven Package', script: 'mvn -B -DskipTests package'
        }
      }
    }

    stage('Frontend Build') {
      steps {
        dir('frontend') {
          bat label: 'Install', script: 'npm ci --no-audit --no-fund'
          bat label: 'Build', script: 'npm run build'
        }
      }
    }

    stage('Docker Build') {
      steps {
        script {
          def BR = env.BRANCH_NAME ?: 'main'
          def beTag = "${REGISTRY_HOST}/${REPO_PREFIX}/${IMAGE_BACKEND}:${BR}-${env.BUILD_NUMBER}"
          def feTag = "${REGISTRY_HOST}/${REPO_PREFIX}/${IMAGE_FRONTEND}:${BR}-${env.BUILD_NUMBER}"
          bat "docker build -t ${beTag} backend"
          bat "docker build -t ${feTag} frontend"
          env.BE_TAG = beTag
          env.FE_TAG = feTag
        }
      }
    }

    stage('Docker Push') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'docker-registry-cred', usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
          // Docker Hub 로그인: 호스트로만 로그인해야 합니다 (네임스페이스 포함 금지)
          bat "echo %REG_PASS% | docker login ${REGISTRY_HOST} -u %REG_USER% --password-stdin"
          bat "docker push ${env.BE_TAG}"
          bat "docker push ${env.FE_TAG}"
        }
      }
    }

    stage('Deploy (manual)') {
      when { expression { return env.BRANCH_NAME == 'main' } }
      steps {
        input message: '배포를 진행할까요?', ok: 'Deploy'
        echo '배포: 윈도우 환경에서는 원격 PowerShell/SSH 전략 중 하나를 선택하세요.'
        echo '예: 원격 리눅스 서버에 SSH로 접속하여 docker compose pull && up -d 실행.'
      }
    }
  }

  post {
    success { echo '파이프라인 성공' }
    failure { echo '파이프라인 실패 — 로그 확인 필요' }
    always { cleanWs deleteDirs: true, notFailBuild: true }
  }
}
