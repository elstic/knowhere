int total_timeout_minutes = 60*2
def knowhere_wheel=''
pipeline {
    agent {
        { label 'arm' }
    }

    options {
        timeout(time: total_timeout_minutes, unit: 'MINUTES')
        buildDiscarder logRotator(artifactDaysToKeepStr: '30')
        parallelsAlwaysFailFast()
        disableConcurrentBuilds(abortPrevious: true)
        preserveStashes(buildCount: 10)
    }
    stages {
        stage("Build"){
            steps {
                    script{
                        withCredentials([usernamePassword(credentialsId: 'ArmLogin', usernameVariable: 'JENKINS_ARM_USERNAME', passwordVariable: 'JENKINS_ARM_PWD')]) {
                                USER = "${JENKINS_ARM_USERNAME}"
                                PWD = "${JENKINS_ARM_PWD}"
                                sh 'sshpass -p ${PWD} ssh zilliz@10.100.31.37 -p 22'
                        }
                        sh 'pwd'
                        def date = sh(returnStdout: true, script: 'date +%Y%m%d').trim()
                        def gitShortCommit = sh(returnStdout: true, script: "echo ${env.GIT_COMMIT} | cut -b 1-7 ").trim()
                        version="${env.CHANGE_ID}.${date}.${gitShortCommit}"
                        sh 'git clean -fxd'
                        sh 'git pull'
                        sh "git config --global --add safe.directory '*'"
                        sh "git submodule update --recursive --init"
                        sh "cd build/ && cmake .. -DCMAKE_BUILD_TYPE=Release -DWITH_UT=ON -DWITH_DISKANN=ON -G Ninja"
                        sh "pwd"
                        sh "ls -la"
                        sh "cd build/ && ninja -v"
                        sh "cd .."
                        sh "cd python  && VERSION=${version} python3 setup.py bdist_wheel"
                        dir('python/dist'){
                        knowhere_wheel=sh(returnStdout: true, script: 'ls | grep .whl').trim()
                        archiveArtifacts artifacts: "${knowhere_wheel}", followSymlinks: false
                        }
                        // stash knowhere info for rebuild E2E Test only
                        sh "echo ${knowhere_wheel} > knowhere.txt"
                        stash includes: 'knowhere.txt', name: 'knowhereWheel'
                    }
            }
        }
        stage("Test"){
            steps {
                script{
                    if ("${knowhere_wheel}"==''){
                        dir ("knowhereWheel"){
                            try{
                                unstash 'knowhereWheel'
                                knowhere_wheel=sh(returnStdout: true, script: 'cat knowhere.txt | tr -d \'\n\r\'')
                            }catch(e){
                                error "No knowhereWheel info remained ,please rerun build to build new package."
                            }
                        }
                    }
                    sh 'rm -rf knowhere-test-feature-2.0'
                    checkout([$class: 'GitSCM', branches: [[name: '*/feature-2.0']], extensions: [],
                    userRemoteConfigs: [[credentialsId: 'milvus-ci', url: 'https://github.com/milvus-io/knowhere-test.git']]])
                    dir('tests'){
                      unarchive mapping: ["${knowhere_wheel}": "${knowhere_wheel}"]
                      sh "ls -lah"
                      sh "nvidia-smi"
                      sh "pip3 install ${knowhere_wheel} \
                          && python3 -m pytest test_hnsw.py::TestHnsw::test_hnsw_recall"
                    }
                }
            }
            post{
                always {
                    script{
                        sh 'cp /tmp/knowhere_ci.log knowhere_ci.log'
                        archiveArtifacts artifacts: 'knowhere_ci.log', followSymlinks: false
                    }
                }
            }
        }

    }
}
