# Build from the repository root:
#   docker build -f server/signaling/Dockerfile -t veilshare-signaling .
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew && ./gradlew :server:signaling:installDist --no-daemon

FROM eclipse-temurin:17-jre
RUN useradd --create-home --uid 10001 veilshare
WORKDIR /app
COPY --from=build /workspace/server/signaling/build/install/signaling/ /app/
USER veilshare
ENV HOST=0.0.0.0
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/app/bin/signaling"]
