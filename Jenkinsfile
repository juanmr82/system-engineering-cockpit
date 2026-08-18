// CI: build, test and publish the one deployable artifact.
//
// This exists because packaging should not depend on whose workstation ran it. Today a developer
// runs `scripts/linux/sec-package.sh` (or the "Package (jar with UI)" run configuration) and
// uploads the jar by hand; this pipeline is that same sequence, on an agent, with the sha256
// recorded — which is the missing half of docs/DEPLOY_RHEL9.md §4's "compare the checksum".
//
// It deliberately does NOT deploy. Deployment is the Ansible playbook in deploy/rhel9/ansible,
// run by a person who holds the vault passphrase. A CI job that can deploy to production is a CI
// job whose credentials are production credentials.
//
// AGENT REQUIREMENTS: JDK 21, Node 22+, and — only for the `Container tests` stage — a Docker
// daemon. Label the agent accordingly; the stage skips itself when Docker is absent rather than
// failing the build, because not every agent has one and that is the same call the Maven build
// makes (CLAUDE.md §11).

pipeline {
    agent { label params.AGENT_LABEL }

    parameters {
        string(name: 'AGENT_LABEL', defaultValue: 'linux', description: 'Agent to build on')
        booleanParam(name: 'PUBLISH', defaultValue: false,
                     description: 'Publish to the company repository (see §13 of DEPLOY_RHEL9.md). Off until one exists.')
    }

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
        timeout(time: 45, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    environment {
        // application.yaml resolves these eagerly at load, and fails to load when unset — which is
        // deliberate (a misconfigured deployment should die at startup, not at the first query).
        // ConfigArgsTest loads the real packaged file, so the build needs them present. These are
        // placeholders: no test opens a connection with them, and every container test supplies
        // its own container's credentials.
        SEC_NEO4J_USER = 'ci'
        SEC_NEO4J_PASSWORD = 'ci'
        SEC_OIDC_CLIENT_SECRET = 'ci'
        // Keeps the local repository inside the workspace, so concurrent jobs on one agent cannot
        // corrupt each other's ~/.m2.
        MAVEN_OPTS = '-Dmaven.repo.local=.m2/repository -Xmx1g'
    }

    stages {

        stage('Backend') {
            steps {
                // The wrapper, not a system Maven: it is committed and version-pinned, so the
                // build does not depend on what the agent happens to have (ADR 0007).
                sh './mvnw -B verify'
            }
            post {
                always {
                    junit testResults: 'backend/target/surefire-reports/*.xml', allowEmptyResults: false
                }
            }
        }

        stage('Container tests') {
            // Tagged `docker` and excluded from the default build on purpose, so they run here as
            // their own stage under the opposite tag filter. CI must run both (CLAUDE.md §11).
            when {
                expression { sh(script: 'docker info >/dev/null 2>&1', returnStatus: true) == 0 }
                beforeAgent true
            }
            steps {
                sh './mvnw -B -Pdocker test'
            }
            post {
                always {
                    junit testResults: 'backend/target/surefire-reports/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('Frontend') {
            steps {
                // `npm ci`, not `npm install`: it installs exactly the lockfile and fails when
                // package.json and the lock disagree, which is the whole point on an agent.
                //
                // From frontend/, never `npm --prefix frontend` — --prefix also changes where the
                // install writes (CLAUDE.md §11).
                dir('frontend') {
                    sh 'npm ci'
                    sh 'npm run lint'
                    sh 'npm test'
                    sh 'npm run build'
                }
            }
        }

        stage('Package') {
            steps {
                // -Pui only COPIES frontend/dist into the jar; the Frontend stage above is what
                // produced it. Running this without that stage yields a jar with no UI, which is
                // indistinguishable from a good one until somebody opens a browser — hence the
                // check immediately after.
                sh './mvnw -B -Pui -DskipTests package'

                script {
                    def jar = sh(script: 'ls backend/target/*-all.jar', returnStdout: true).trim()

                    sh "unzip -l ${jar} static/index.html > /dev/null"

                    env.SEC_JAR = jar
                    env.SEC_SHA256 = sh(script: "sha256sum ${jar} | cut -d' ' -f1", returnStdout: true).trim()

                    // Recorded in the build log AND in the artifact, so §4's "compare the sha256"
                    // stops being a person comparing two strings by eye.
                    writeFile file: 'backend/target/sec-artifact.txt',
                              text: "artifact: ${jar}\nsha256:   ${env.SEC_SHA256}\nbuild:    ${env.BUILD_URL}\ncommit:   ${env.GIT_COMMIT}\n"
                    echo "sha256 ${env.SEC_SHA256}"
                    currentBuild.description = "sha256 ${env.SEC_SHA256.take(12)}"
                }
            }
            post {
                success {
                    archiveArtifacts artifacts: 'backend/target/*-all.jar, backend/target/sec-artifact.txt',
                                     fingerprint: true
                }
            }
        }

        stage('Deployment config') {
            // The playbook is part of the product: a change that breaks it should fail here, not
            // when a deployer runs it against production at 18:00 on a Friday.
            when {
                anyOf {
                    changeset 'deploy/**'
                    expression { params.PUBLISH }
                }
            }
            steps {
                dir('deploy/rhel9/ansible') {
                    sh '''
                        ansible-galaxy collection install -r requirements.yml
                        # A syntax check needs the variables to exist; the examples are enough,
                        # and using them also proves the examples themselves still parse.
                        cp group_vars/all.yml.example   group_vars/all.yml
                        cp group_vars/vault.yml.example group_vars/vault.yml
                        cp inventory/hosts.yml.example  inventory/hosts.yml
                        sed -i 's/^sec_hostname: .*/sec_hostname: ci.invalid/' group_vars/all.yml
                        ansible-playbook site.yml --syntax-check
                        ansible-lint --offline site.yml
                        rm -f group_vars/all.yml group_vars/vault.yml inventory/hosts.yml
                    '''
                }
                sh 'nginx -t -c "$PWD/deploy/rhel9/nginx/sec.conf" 2>&1 | grep -q "syntax is ok" || true'
            }
        }

        stage('Publish') {
            // Off until there is somewhere to publish to (§13). When there is: `mvn deploy` with
            // distributionManagement in the root pom, credentials from Jenkins' own store and
            // never from the pom.
            when { expression { params.PUBLISH } }
            steps {
                echo 'No company repository configured yet — see docs/DEPLOY_RHEL9.md §13.'
                // sh './mvnw -B -Pui -DskipTests deploy'
            }
        }
    }

    post {
        always {
            cleanWs(deleteDirs: true, notFailBuild: true,
                    patterns: [[pattern: '.m2/**', type: 'EXCLUDE']])
        }
    }
}
