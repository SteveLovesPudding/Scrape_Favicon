Instructions:

1. To initialize the mysql db that's used to persist favicon

    docker-compose up &

2. To initialize the application, we have two options

    i) The more simple one
    
        sbt run

    ii) Package as an object and then run

        sbt dist
        unzip target/universal/steve-favicon-finder-1.0-SNAPSHOT.zip
        steve-favicon-finder-1.0-SNAPSHOT/bin/steve-favicon-finder -Dplay.http.secret.key="favicon" &

3. To seed the database. Run seed.py to ping the server. You can adjust the csv file, the number of concurrent requests, and target in the beginning of the script.

        python seed.py &

        You can track the success/failures through
        seed_success.txt
        seed_fail.txt


