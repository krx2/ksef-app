# Stage 1: Install dependencies
FROM node:20-alpine AS deps
WORKDIR /app
COPY package.json ./
RUN npm install

# Stage 2: Build
FROM node:20-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

# Backend URL used by the Next.js server-side proxy (rewrites).
# Must match the docker-compose backend service name.
ARG NEXT_PUBLIC_BACKEND_URL=http://backend:8080
ENV NEXT_PUBLIC_BACKEND_URL=$NEXT_PUBLIC_BACKEND_URL

RUN npm run build

# Stage 3: Runtime (minimal standalone image)
FROM node:20-alpine AS runner
WORKDIR /app

ENV NODE_ENV=production

RUN addgroup -S ksef && adduser -S ksef -G ksef

COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static

USER ksef
EXPOSE 3000
CMD ["node", "server.js"]
