// Builds the authorizer jar and publishes it to repo.hops.works.
//
// The build runs inside a container rather than on the agent, and that is the point: the
// Jenkins agent is launched with /usr/lib/jvm/java-8-openjdk-amd64, and a Java 8 javac
// cannot read the kafka-clients 4.x class files this project compiles against. On the
// agent the build fails with
//
//     bad class file: .../kafka-clients-4.3.0.jar(org/apache/kafka/common/Endpoint.class)
//       class file has wrong version 55.0, should be 52.0
//
// The image supplies its own JDK, so the toolchain no longer depends on what happens to be
// installed on the host. clusterj-onlinefs builds against Kafka 4.3.1 the same way.
//
// Keeping this in the repo rather than in job config means the build, the version it
// publishes and the JDK it uses all move together with the source.
pipeline {
    agent {
        docker {
            // Temurin 21 builds a project that targets 17. Match clusterj-onlinefs so both
            // Kafka 4.x builds share one toolchain.
            image 'maven:3.9.11-eclipse-temurin-21'
            // The .m2 mount keeps downloads across builds. /opt/repository is the directory
            // repo.hops.works/master serves, and the publish stage writes into it, so it has
            // to be visible from inside the container.
            args '-v $HOME/.m2:/var/maven/.m2 -e MAVEN_CONFIG=/var/maven/.m2 -v /opt/repository:/opt/repository'
        }
    }

    stages {
        stage('build') {
            steps {
                sh 'mvn -Duser.home=/var/maven -U clean package'
            }
        }

        // A separate stage, not a post-success step: the pipeline stops here if the build
        // failed, so a compile error can no longer be followed by a "cp: cannot stat" that
        // buries the real cause.
        stage('publish') {
            steps {
                sh '''
                    set -eu
                    # Read the version from the pom rather than an injected variable. The
                    # freestyle job this replaced carried a stale POM_VERSION and published
                    # under 1.4.1-SNAPSHOT while the pom said 5.2.0-SNAPSHOT.
                    VERSION=$(mvn -Duser.home=/var/maven -q -DforceStdout help:evaluate -Dexpression=project.version)
                    JAR="target/hops-kafka-authorizer-${VERSION}.jar"
                    DEST="/opt/repository/master/hops-kafka-authorizer/${VERSION}"

                    test -f "$JAR"
                    mkdir -p "$DEST"
                    cp "$JAR" "$DEST/"

                    # docker-images/strimzi-kafka pins this checksum and fails its build if it
                    # does not match, so print it here: a jar rebuilt on a different toolchain
                    # is not guaranteed to be byte-identical, and AUTHORIZER_SHA256 has to be
                    # re-pinned from whatever is actually published.
                    echo "published ${VERSION} to ${DEST}"
                    sha256sum "$DEST/hops-kafka-authorizer-${VERSION}.jar"
                '''
            }
        }
    }
}
