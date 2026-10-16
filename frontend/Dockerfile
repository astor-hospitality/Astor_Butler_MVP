# The C3 studio site (c3ag.ru): Next.js 16, standalone output, port 3001 (the compose files may set PORT).
# The media (photos, video, fonts) is NOT in the image: it is read from S3 by NEXT_PUBLIC_MEDIA_BASE_URL.
# Every NEXT_PUBLIC_* value is baked into the bundle at build time and is public. See DEPLOY_C3_SITE.md.
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:22-alpine AS build
WORKDIR /app
ARG NEXT_PUBLIC_SITE_URL=https://c3ag.ru
ARG NEXT_PUBLIC_MEDIA_BASE_URL=
ARG NEXT_PUBLIC_WEB_CHAT_ENDPOINT=/api/chat
ARG NEXT_PUBLIC_POLICY_URL=/docs/policy.html
ENV NEXT_PUBLIC_SITE_URL=$NEXT_PUBLIC_SITE_URL
ENV NEXT_PUBLIC_MEDIA_BASE_URL=$NEXT_PUBLIC_MEDIA_BASE_URL
ENV NEXT_PUBLIC_WEB_CHAT_ENDPOINT=$NEXT_PUBLIC_WEB_CHAT_ENDPOINT
ENV NEXT_PUBLIC_POLICY_URL=$NEXT_PUBLIC_POLICY_URL
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# a server image without the media address would show empty frames: stop here instead
RUN test -n "$NEXT_PUBLIC_MEDIA_BASE_URL" || (echo "NEXT_PUBLIC_MEDIA_BASE_URL is required (S3 address of the media, see DEPLOY_C3_SITE.md)" >&2; exit 1)
RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3001
ENV HOSTNAME=0.0.0.0
RUN addgroup -S nextjs && adduser -S nextjs -G nextjs
COPY --from=build --chown=nextjs:nextjs /app/public ./public
COPY --from=build --chown=nextjs:nextjs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nextjs /app/.next/static ./.next/static
USER nextjs
EXPOSE 3001
CMD ["node", "server.js"]
