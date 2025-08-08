FROM golang:1.24-bullseye

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

RUN apt-get update && apt-get install -y --no-install-recommends \
    wget unzip ca-certificates openjdk-17-jdk && \
    rm -rf /var/lib/apt/lists/*

# Install Android command line tools
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools && \
    cd /tmp && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdtools.zip && \
    unzip -q cmdtools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools && \
    mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest && \
    rm -f cmdtools.zip

ENV PATH=${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools

# Accept licenses and install required SDK components
RUN yes | sdkmanager --licenses && \
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;23.1.7779620"

# Install gomobile/gobind and init
RUN go env -w GO111MODULE=on && \
    go env -w GOPROXY=https://proxy.golang.org,direct && \
    go install golang.org/x/mobile/cmd/gobind@latest && \
    go install golang.org/x/mobile/cmd/gomobile@latest && \
    /root/go/bin/gomobile init

WORKDIR /work

# Copy only the Go mobile package
COPY mobile /work/mobile

# Build the AAR
RUN cd /work/mobile && \
    /root/go/bin/gomobile bind -androidapi 24 -target=android -javapkg com.qdynn.dnstt.mobile -o /work/dnstt.aar .