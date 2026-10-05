FROM nginx:1.27-alpine
COPY site/ /usr/share/nginx/html/
COPY frontend.nginx.conf /etc/nginx/nginx.conf
USER 101:101
EXPOSE 8080
ENTRYPOINT ["nginx", "-g", "daemon off;"]
