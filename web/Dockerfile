# ---------- 构建阶段 ----------
FROM node:22-alpine AS build
WORKDIR /build
COPY package*.json ./
RUN npm install --no-audit --no-fund
COPY . .
RUN npm run build

# ---------- 运行阶段 ----------
FROM nginx:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
