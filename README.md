### Akka Stream & Http working code examples

### buzzwords

reactive, stream, flow, blueprint, buffer, backpressure, downstream, upstream, materialization, runnable graph, processing stage, shape, partial graph, source, sink, composition, graph builder, merge, zip, balance, broadcast, bidi, supervision, junctions, inlet, outlet, closed graph, push, pull, liveness, boundness, pipelining, fan-in, fan-out, producer, subscriber

### facts

-   open source, Apache 2 license
-   Scala (2.10+) and Java (6+) API
-   stable version 1.0 from July 2015
-   fully functional model but 
-   not yet optimized for performance!

### motivation

-   we got the best Actors (akka) but 
-   real-world data flow scenarios
-   should be easier to design and implement
-   so let's invent AkkaStreams! 
-   and redesign Spray to use them (Akka Http!)

### in a few words

Akka Stream is a: 

-   processing model separating WHAT and HOW
-   high-level Flow and Graph API
-   DSL for type-safe stream creation and composing 
-   decoupled Akka Actor based execution model
-   set of predefined “processing stages”

### main features

-   Reactive Stream 1.0 compliant (http://www.reactive-streams.org/)
-   Blueprints: lightweight, immutable, reusable, composable stages
-   Materialization: transformation into Actors-based runtime
-   Allows optimization, validation, cluster-deployment, etc.
-   Bounded, non-droping buffers by default
-   Backpressure supported on all stages

### basic ideas

-   Stream: An active process that involves moving and transforming data.
-   Element: An element is the processing unit of streams. All operations transform and transfer elements from upstream to downstream. Buffer sizes are always expressed as number of elements independently form the actual size of the elements.
-   Back-pressure: A means of flow-control, a way for consumers of data to notify a producer about their current availability, effectively slowing down the upstream producer to match their consumption speeds. In the context of Akka Streams back-pressure is always understood as non-blocking and asynchronous.
-   Non-Blocking: Means that a certain operation does not hinder the progress of the calling thread, even if it takes long time to finish the requested operation.

### flow model

-   Source: A processing stage with exactly one output, emitting data elements whenever downstream processing stages are ready to receive them.
-   Sink: A processing stage with exactly one input, requesting and accepting data elements possibly slowing down the upstream producer of elements
-   Flow: A processing stage which has exactly one input and output, which connects its up- and downstreams by transforming the data elements flowing through it.
-   RunnableGraph: A Flow that has both ends "attached" to a Source and Sink respectively

### stage vs. shape

-  Stage: concurrent, non-blocking processing unit, transformed to an Actor
-  stages are asynchronous and pipelined by default which means that a stage, after handing out an element to its downstream consumer is able to immediately process the next message
-  Shape: conceptual "box" with input and output ports where elements to be processed arrive and leave the stage: inlets and outlets
-  each Stage has some Shape! ex: SourceShape,FlowShape, FanIn2Shape,FanOut3Shape, BidiShape, ClosedShape, etc.
-  Source, Flow and Sink are linear stages having simple shapes, with single outlet and/or inlet, are used to build fluent chains of processing
-  graphs are needed whenever you want to perform any kind of complex processing involving multiple-inputs or multiple-outputs operations
-  custom stages and shape can be easily defined and composed

### graph building

Graphs are complex stages built from:

-  simple flows: Flow, Source, Sink
-  fan-in junctions (multiple inputs): Merge, MergePreferred, Zip, ZipWith, Concat or FlexiMerge
-  fan-out junctions (multiple outputs): Broadcast, Balance, Unzip, UnzipWith or FlexiRoute
-  nested graphs

Each graph is a stage and has some Shape. 
Graphs with linear shapes can be wrapped as a Source, Flow or Sink.

### materialization

-   materialization creates a new running network corresponding to the blueprint that was encoded in the provided RunnableGraph
-   each materialization returns a different object, inaccessible from the outside
-   can return materialized value, optionally providing a controlled interaction capability with the network
-   can combine the materialized values as well

### let's talk about buffers

-   like actors every processing stage has its implicit, bounded, non-dropping buffer
-   implicit buffer size of 1 would be the most natural choice if there would be no need for throughput improvements, default max size is 16
-   it is recommended to keep implicit buffer sizes small
-   it is possible to provide explicit user defined buffers that are part of the domain logic of the stream processing pipeline of an application
-   explicit buffers are declared with overflow strategy, one of: backpressure, dropBuffer, dropHead, dropNew, dropTail, fail

### error handling

-   Strategies for how to handle exceptions from processing stream elements can be defined when materializing the stream. 
-   The error handling strategies are inspired by actor supervision strategies, but the semantics have been adapted to the domain of stream processing.
-   There are three ways to handle exceptions from application code:
-   -   Stop - The stream is completed with failure.
-   -   Resume - The element is dropped and the stream continues.
-   -   Restart - The element is dropped and the stream continues after restarting the stage. Restarting a stage means that any accumulated state is cleared. This is typically performed by creating a new instance of the stage.
-   By default the stopping strategy is used for all exceptions, i.e. the stream will be completed with failure when an exception is thrown.

### external integration

-  to expose stream as an actor you can use Source.actorRef
-  for piping the elements of a stream as messages to an ordinary actor you can use the Sink.actorRef
-  for more advanced use cases the ActorPublisher and ActorSubscriber traits are provided to support implementing Reactive Streams Publisher and Subscriber with an Actor
-  stream transformations and side effects involving external non-stream based services can be performed with mapAsync or mapAsyncUnordered
-  Akka Streams is a Reactive Streams compliant library which makes it possible to plug together with other stream libraries

### http (formerly spray)

-   Akka HTTP modules implement a full server- and client-side HTTP stack on top of akka-actor and akka-stream
-   not a web-framework but rather a more general toolkit for providing and consuming HTTP-based services
-   open design and many times offers several different API levels for "doing the same thing"
-   rich Routing API
-   supports HTTPS and WebSockets









