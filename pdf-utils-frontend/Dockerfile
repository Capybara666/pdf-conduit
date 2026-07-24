# Multi-stage build for the PDF Conduit Angular frontend.
#
#   docker build -t pdf-conduit-frontend .
#   docker run --rm -p 8081:80 pdf-conduit-frontend
#
# In docker-compose the reverse proxy forwards /api to the `backend` service
# (see nginx.conf). Standalone, /api requests 502 until a backend is reachable.

# ---- Stage 1: build the Angular app ----------------------------------------
FROM node:22-alpine AS build
WORKDIR /app

# Install deps first for better layer caching.
COPY package.json package-lock.json ./
RUN npm ci

# Build the production bundle → dist/pdf-utils-frontend/browser
COPY . .
RUN npm run build

# ---- Stage 2: serve the static bundle with nginx ---------------------------
FROM nginx:alpine AS runtime

# SPA server config (history fallback + /api proxy).
COPY nginx.conf /etc/nginx/conf.d/default.conf

# The Angular `application` builder emits the site under browser/.
COPY --from=build /app/dist/pdf-utils-frontend/browser /usr/share/nginx/html

EXPOSE 80
# 127.0.0.1, not localhost: inside the container localhost can resolve to ::1
# first, and nginx listens on IPv4 only — busybox wget would then report
# "Connection refused" and mark a perfectly healthy container unhealthy.
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1

CMD ["nginx", "-g", "daemon off;"]
