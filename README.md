LOAD BALANCING
A load balancer is a component that, once invoked, it distributes incoming requests to a list of registered providers and return the value obtained from one of the registered providers to the original caller. For simplicity we will consider both the load balancer and the provider having a public method named get()

install sbt
compile sbt clean compile
test: sbt test