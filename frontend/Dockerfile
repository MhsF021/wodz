FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.30.5-alpine
RUN apk add --no-cache ca-certificates
ENV PORT=80
ENV BACKEND_URL=http://backend:8080
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY security-headers.conf /etc/nginx/wodz-security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
