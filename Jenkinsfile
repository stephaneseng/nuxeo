/*
 * (C) Copyright 2019 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 *     Thomas Roger <troger@nuxeo.com>
 */

dockerNamespace = 'nuxeo'
repositoryUrl = 'https://github.com/nuxeo/nuxeo'
testEnvironments= [
  'dev',
  'mongodb',
  'postgresql',
]

properties([
  [$class: 'GithubProjectProperty', projectUrlStr: repositoryUrl],
  [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', daysToKeepStr: '60', numToKeepStr: '60', artifactNumToKeepStr: '5']],
  disableConcurrentBuilds(),
])

void setGitHubBuildStatus(String context, String message, String state) {
  step([
    $class: 'GitHubCommitStatusSetter',
    reposSource: [$class: 'ManuallyEnteredRepositorySource', url: repositoryUrl],
    contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: context],
    statusResultSource: [$class: 'ConditionalStatusResultSource', results: [[$class: 'AnyBuildResult', message: message, state: state]]],
  ])
}

String getMavenArgs() {
  def args = '-B -nsu'
  if (!isPullRequest()) {
    args += ' -Prelease'
  }
  return args
}

def isPullRequest() {
  return BRANCH_NAME =~ /PR-.*/
}

String getVersion() {
  return isPullRequest() ? getPullRequestVersion() : getReleaseVersion()
}

String getReleaseVersion() {
  String nuxeoVersion = readMavenPom().getVersion()
  String noSnapshot = nuxeoVersion.replace("-SNAPSHOT", "")
  String version = noSnapshot + '.0' // first version ever

  // find the latest tag if any
  sh "git fetch origin 'refs/tags/v${noSnapshot}*:refs/tags/v${noSnapshot}*'"
  def tag = sh(returnStdout: true, script: "git tag --sort=committerdate --list 'v${noSnapshot}*' | tail -1 | tr -d '\n'")
  if (tag) {
    container('maven') {
      version = sh(returnStdout: true, script: "semver bump patch ${tag} | tr -d '\n'")
    }
  }
  return version
}

String getPullRequestVersion() {
  return "${BRANCH_NAME}-" + readMavenPom().getVersion()
}

String getDockerTagFrom(String version) {
  return version.tokenize('.')[0] + '.x'
}

void runFunctionalTests(String baseDir) {
  try {
    sh "mvn ${MAVEN_ARGS} -f ${baseDir}/pom.xml verify"
  } finally {
    try {
      archiveArtifacts allowEmptyArchive: true, artifacts: "${baseDir}/**/target/failsafe-reports/*, ${baseDir}/**/target/**/*.log, ${baseDir}/**/target/*.png, ${baseDir}/**/target/**/distribution.properties, ${baseDir}/**/target/**/configuration.properties"
    } catch (err) {
      echo hudson.Functions.printThrowable(err)
    }
  }
}

void dockerPull(String image) {
  sh "docker pull ${image}"
}

void dockerRun(String image, String command, String user = null) {
  String userOption = user ? "--user=${user}" : ''
  sh "docker run --rm ${userOption} ${image} ${command}"
}

void dockerTag(String image, String tag) {
  sh "docker tag ${image} ${tag}"
}

void dockerPush(String image) {
  sh "docker push ${image}"
}

void dockerDeploy(String imageName) {
  String fullImageName = "${dockerNamespace}/${imageName}"
  String fixedVersionInternalImage = "${DOCKER_REGISTRY}/${fullImageName}:${VERSION}"
  String latestInternalImage = "${DOCKER_REGISTRY}/${fullImageName}:${DOCKER_TAG}"

  dockerPull(fixedVersionInternalImage)
  echo "Push ${latestInternalImage}"
  dockerTag(fixedVersionInternalImage, latestInternalImage)
  dockerPush(latestInternalImage)
}

/**
 * Replaces environment variables present in the given yaml file and then runs skaffold build on it.
 * Needed environment variables are generally:
 * - DOCKER_REGISTRY
 * - VERSION
 */
void skaffoldBuild(String yaml) {
  sh """
    envsubst < ${yaml} > ${yaml}~gen
    skaffold build -f ${yaml}~gen
  """
}

void skaffoldBuildAll() {
  // build builder and base images
  skaffoldBuild('docker/skaffold.yaml')
  // build images depending on the builder and/or base images, waiting for dependent images support in skaffold
  skaffoldBuild('docker/slim/skaffold.yaml')
  skaffoldBuild('docker/nuxeo/skaffold.yaml')
}

def buildUnitTestStage(env) {
  def isDev = env == 'dev'
  def testNamespace = "${TEST_NAMESPACE_PREFIX}-${env}"
  def redisHost = "${TEST_REDIS_RESOURCE}.${testNamespace}.${TEST_SERVICE_DOMAIN_SUFFIX}"
  return {
    stage("Run ${env} unit tests") {
      container('maven') {
        script {
          setGitHubBuildStatus("platform/utests/${env}", "Unit tests - ${env} environment", 'PENDING')
          try {
            echo """
            ----------------------------------------
            Run ${env} unit tests
            ----------------------------------------"""

            echo "${env} unit tests: install external services"
            // initialize Helm without Tiller and add local repository
            sh """
              helm init --client-only
              helm repo add ${HELM_CHART_REPOSITORY_NAME} ${HELM_CHART_REPOSITORY_URL}
            """
            // prepare values to disable nuxeo and activate external services in the nuxeo Helm chart
            sh 'envsubst < ci/helm/nuxeo-test-base-values.yaml > nuxeo-test-base-values.yaml'
            def testValues = '--set-file=nuxeo-test-base-values.yaml'
            if (!isDev) {
              sh "envsubst < ci/helm/nuxeo-test-${env}-values.yaml > nuxeo-test-${env}-values.yaml"
              testValues += " --set-file=nuxeo-test-${env}-values.yaml"
            }
            // install the nuxeo Helm chart into a dedicated namespace that will be cleaned up afterwards
            sh """
              jx step helm install ${HELM_CHART_REPOSITORY_NAME}/${HELM_CHART_NUXEO} \
                --name=${TEST_HELM_CHART_RELEASE} \
                --namespace=${testNamespace} \
                ${testValues}
            """
            // wait for Redis to be ready
            sh """
              kubectl rollout status statefulset ${TEST_REDIS_RESOURCE} \
                --namespace=${testNamespace} \
                --timeout=${TEST_DEFAULT_ROLLOUT_STATUS_TIMEOUT}
            """
            if (!isDev) {
              // wait for Elasticsearch to be ready
              sh """
                kubectl rollout status deployment ${TEST_ELASTICSEARCH_RESOURCE} \
                  --namespace=${testNamespace} \
                  --timeout=${TEST_ELASTICSEARCH_ROLLOUT_STATUS_TIMEOUT}
              """
              // wait for MongoDB or PostgreSQL to be ready
              def resourceType = env == 'mongodb' ? 'deployment' : 'statefulset'
              sh """
                kubectl rollout status ${resourceType} ${TEST_HELM_CHART_RELEASE}-${env} \
                  --namespace=${testNamespace} \
                  --timeout=${TEST_DEFAULT_ROLLOUT_STATUS_TIMEOUT}
              """
            }

            echo "${env} unit tests: run Maven"
            // prepare test framework system properties
            sh """
              CHART_RELEASE=${TEST_HELM_CHART_RELEASE} SERVICE=${env} NAMESPACE=${testNamespace} DOMAIN=${TEST_SERVICE_DOMAIN_SUFFIX} \
                envsubst < ci/mvn/nuxeo-test-${env}.properties > ${HOME}/nuxeo-test-${env}.properties
            """
            // run unit tests:
            // - in nuxeo-core and dependent projects only (nuxeo-common and nuxeo-runtime are run in dedicated stages)
            // - for the given environment (see the customEnvironment profile in pom.xml):
            //   - in an alternative build directory
            //   - loading some test framework system properties
            def testCore = env == 'mongodb' ? 'mongodb' : 'vcs'
            sh """
              mvn ${MAVEN_ARGS} -rf nuxeo-core \
                -Dcustom.environment=${env} \
                -Dnuxeo.test.core=${testCore} \
                -Dnuxeo.test.redis.host=${redisHost} \
                test
            """

            setGitHubBuildStatus("platform/utests/${env}", "Unit tests - ${env} environment", 'SUCCESS')
          } catch(err) {
            setGitHubBuildStatus("platform/utests/${env}", "Unit tests - ${env} environment", 'FAILURE')
            throw err
          } finally {
            try {
              junit testResults: "**/target-${env}/surefire-reports/*.xml"
            } finally {
              echo "${env} unit tests: clean up test namespace"
              // uninstall the nuxeo Helm chart
              sh """
                jx step helm delete ${TEST_HELM_CHART_RELEASE} \
                  --namespace=${testNamespace} \
                  --purge
              """
              // clean up the test namespace
              sh "kubectl delete namespace ${testNamespace} --ignore-not-found=true"
            }
          }
        }
      }
    }
  }
}

pipeline {
  agent {
    label 'jenkins-nuxeo-platform-11'
  }
  environment {
    // force ${HOME}=/root - for an unexplained reason, ${HOME} is resolved as /home/jenkins though sh 'env' shows HOME=/root
    HOME = '/root'
    HELM_CHART_REPOSITORY_NAME = 'local-jenkins-x'
    HELM_CHART_REPOSITORY_URL = 'http://jenkins-x-chartmuseum:8080'
    HELM_CHART_NUXEO = 'nuxeo'
    TEST_HELM_CHART_RELEASE = 'test-release'
    TEST_NAMESPACE_PREFIX = "nuxeo-unit-tests-$BRANCH_NAME-$BUILD_NUMBER".toLowerCase()
    TEST_SERVICE_DOMAIN_SUFFIX = 'svc.cluster.local'
    TEST_REDIS_RESOURCE = "${TEST_HELM_CHART_RELEASE}-redis-master"
    TEST_ELASTICSEARCH_RESOURCE = "${TEST_HELM_CHART_RELEASE}-elasticsearch-client"
    TEST_DEFAULT_ROLLOUT_STATUS_TIMEOUT = '1m'
     // Elasticsearch might take longer
    TEST_ELASTICSEARCH_ROLLOUT_STATUS_TIMEOUT = '3m'
    BUILDER_IMAGE_NAME = 'builder'
    BASE_IMAGE_NAME = 'base'
    NUXEO_IMAGE_NAME = 'nuxeo'
    SLIM_IMAGE_NAME = 'slim'
    // waiting for https://jira.nuxeo.com/browse/NXBT-3068 to put it in Global EnvVars
    PUBLIC_DOCKER_REGISTRY = 'docker.packages.nuxeo.com'
    MAVEN_OPTS = "$MAVEN_OPTS -Xms512m -Xmx3072m"
    MAVEN_ARGS = getMavenArgs()
    VERSION = getVersion()
    DOCKER_TAG = getDockerTagFrom("${VERSION}")
    CHANGE_BRANCH = "${env.CHANGE_BRANCH != null ? env.CHANGE_BRANCH : BRANCH_NAME}"
    CHANGE_TARGET = "${env.CHANGE_TARGET != null ? env.CHANGE_TARGET : BRANCH_NAME}"
    ORG = "nuxeo"
    APP_NAME = "nuxeo"
    NAMESPACE = "$APP_NAME-${BRANCH_NAME.toLowerCase()}"
    REFERENCE_BRANCH = "master"
    PERSISTENCE = "${BRANCH_NAME == REFERENCE_BRANCH}"
  }

  stages {
    stage('Set labels') {
      steps {
        container('maven') {
          echo """
          ----------------------------------------
          Set Kubernetes resource labels
          ----------------------------------------
          """
          echo "Set label 'branch: ${BRANCH_NAME}' on pod ${NODE_NAME}"
          sh """
            kubectl label pods ${NODE_NAME} branch=${BRANCH_NAME}
          """
        }
      }
    }

    stage('Deploy Preview') {
      steps {
        setGitHubBuildStatus('nuxeo/preview/deploy', 'Deploy nuxeo Preview', 'PENDING')
        container('maven') {
          dir('helm/preview') {
            echo """
            ----------------------------------------
            Deploy Preview environment
            ----------------------------------------"""
            // first substitute docker image names and versions
            sh """
              mv values.yaml values.yaml.tosubst
              envsubst < values.yaml.tosubst > values.yaml
            """
            // second create target namespace (if doesn't exist) and copy secrets to target namespace
            script {
              sh(returnStdout: true, script: 'jx -b ns | sed -r "s/^Using namespace \'([^\']+)\'.+\\$/\\1/"')
              boolean nsExist = sh(returnStatus: true, script: "kubectl get namespace ${NAMESPACE}") == 0
              String noCommentOpt = '';
              if (nsExist) {
                noCommentOpt = '--no-comment'
              } else {
                sh "kubectl create namespace ${NAMESPACE}"
              }
              sh "kubectl create secret generic preview-docker-cfg --namespace=${NAMESPACE} --from-file=.dockerconfigjson=/home/jenkins/.docker/config.json --type=kubernetes.io/dockerconfigjson --dry-run -o yaml | kubectl apply -f -"
              // third build and deploy the chart
              // waiting for https://github.com/jenkins-x/jx/issues/5797 to be fixed in order to remove --source-url
              sh """
                jx step helm build --verbose
                mkdir target && helm template . --output-dir target
                jx preview \
                  --namespace ${NAMESPACE} \
                  --verbose \
                  --source-url=https://github.com/nuxeo/nuxeo \
                  --preview-health-timeout 15m \
                  ${noCommentOpt}
              """
            }
          }
        }
      }
      post {
        always {
          archiveArtifacts allowEmptyArchive: true, artifacts: '**/requirements.lock, **/charts/*.tgz, **/target/**/*.yaml'
        }
        success {
          setGitHubBuildStatus('nuxeo/preview/deploy', 'Deploy nuxeo Preview', 'SUCCESS')
        }
        failure {
          setGitHubBuildStatus('nuxeo/preview/deploy', 'Deploy nuxeo Preview', 'FAILURE')
        }
      }
    }
  }

  post {
    always {
      script {
        if (!isPullRequest()) {
          // update JIRA issue
          step([$class: 'JiraIssueUpdater', issueSelector: [$class: 'DefaultIssueSelector'], scm: scm])
        }
      }
    }
  }
}
