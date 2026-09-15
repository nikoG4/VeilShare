FROM gradle:8-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle :server:signaling:installDist --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/server/signaling/build/install/signaling /app/signaling
CMD ["/app/signaling/bin/signaling"]
