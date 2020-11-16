![Scala CI](https://github.com/baracuda25/loadBalancer/workflows/Scala%20CI/badge.svg?branch=main&event=push)

## LOAD BALANCING

*A load balancer* is a component that, once invoked, it distributes incoming
requests to a list of registered providers and return the value obtained 
from one of the registered providers to the original caller. 
For simplicity we will consider both the load balancer and the provider having
a public method named get()

1. install sbt
2. compile `sbt clean compile`
3. test: `sbt test`
