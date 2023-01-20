# set up a build pod
```
k run diffy-build --rm -it --image maven:3.8.6-openjdk-18 -- bash
```
and then checkout diffy repo inside.

# run example services

```
javac -d example src/test/scala/ai/diffy/examples/http/ExampleServers.java

java -cp example ai.diffy.examples.http.ExampleServers 9000 9100 9200
```

# run diffy
build first
```
mvn package
```
then run it
```
java \
-Djdk.httpclient.allowRestrictedHeaders=host,connection,content-length,expect,upgrade \
-jar ./target/diffy.jar \
--candidate='localhost:9200' \
--master.primary='localhost:9000' \
--master.secondary='localhost:9100' \
--responseMode='candidate' \
--service.protocol='http' \
--serviceName='ExampleService' \
--proxy.port=8880 \
--http.port=8888 \
--allowHttpSideEffects=true \
--logging.level.ai.diffy.proxy.HttpDifferenceProxy=DEBUG
```

# send traffic
```
curl -XPOST -s -i -H "host:www.xx.com" -H "Canonical-Resource:json" http://localhost:8880/json?Mixpanel -d '{"a": 1}'
```

# Check dashboard
```
k port-forward <container> 8888:8888
```

# push to ECR
```
aws ecr get-login-password --region us-east-2 --profile=admin | docker login --username AWS --password-stdin 242230929264.dkr.ecr.us-east-2.amazonaws.com
docker buildx build --platform linux/amd64 -f Dockerfile --push -t 242230929264.dkr.ecr.us-east-2.amazonaws.com/diffy:0.0.2 .
```