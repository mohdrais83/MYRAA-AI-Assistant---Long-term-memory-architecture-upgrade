FROM node:22-bookworm-slim AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --include=dev

COPY backend ./backend
COPY src/lib/memoryTypes.ts ./src/lib/memoryTypes.ts

RUN npm run backend:build

FROM node:22-bookworm-slim AS runtime

ENV NODE_ENV=production

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts

COPY --from=build --chown=node:node /app/dist/backend/server.js ./dist/backend/server.js

USER node

EXPOSE 10000

CMD ["node", "dist/backend/server.js"]
