# TechAid Api

Provides backing services for the dashboard site at https://lambeth-techaid.ju.ma/app/

```bash
# It's best to run with docker 
# To build the codebase and start the service locally on http://localhost:8080
# Copy the sample config file first 
cp .env.sample .env
# Start the app
./gradlew ktlintFormat build -x test && docker-compose up 
```

## Generating Gmail Credentials 
```bash
# Get the code for permission request 
https://accounts.google.com/o/oauth2/v2/auth \
 -d client_id=<client_id> \
 -d response_type=code \
 -d scope=https://www.googleapis.com/auth/gmail.modify \
 -d redirect_uri=http://localhost \
 -d access_type=offline
 
# Exchange code for access token and refresh token 
curl -X POST \
 -d code=<code> \
 -d client_id=<client_id> \
 -d client_secret=<client_secret> \
 -d grant_type=<grant_type> \
 -d redirect_uri=<redirect_uri> \
  https://www.googleapis.com/oauth2/v4/token
```

## Setting up for the dev environment

The `setup_branch` contains the docker files necessary for the dev environment. Do the following

- Copy the `.env.sample` file to a `.env` (create if one does not already exist)
- Run `docker compose up -d` to run the containers (`-d` option runs it in the background)
- Execute the following command to obtain a bash shell of the container running the server api:
    `docker exec -it techapi-server-web-1 bash`
- Once in the container, execute the following command to run the application:
    `./gradlew bootRun -PskipDownload=true`
- The server should now be running and accessible at [http://localhost:8080](http://localhost:8080). 

You can now use PostMan or other similar clients to test the api. Or you can follow the instructions for running the frontend application on the local machine. 

After editing any files, press Crtl+C and rerun the last command (`./gradlew bootRun -PskipDownload=true`) to reload. 

### Notes
Ideally, you can attach VSCode to a running container using DevContainer options. But this is currently not working due to some bug in VSCode itself, I'm presuming. Feel free to give it a shot to see if it works. Or you could just use IntelliJ for a less painful experience.  