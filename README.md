Scalatron - Learn Scala With Friends
=========

This is the source code for Scalatron, a multi-player programming game in which coders pit bot programs
(written in Scala) against each other. It is an educational resource for groups of programmers or individuals that
want to learn more about the Scala programming language or want to hone their Scala programming skills. 

For more information, [visit the Scalatron web site here on Github](http://scalatron.github.com).

To stay current, follow Scalatron on Twitter at [@scalatron](http://twitter.com/scalatron).

### WSL SetUp
=======

* If sbt unavailable then install. Steps - https://www.scala-sbt.org/1.x/docs/Installing-sbt-on-Linux.html
* Run `sbt dist` for creating the dist package.

### Running The Scalatron Server
===

* Navigate to the `dist/bin` directory and run: `java -jar Scalatron.jar`. This should open-up the arena UI.
* Navigate to `http://localhost:8080` and create users. Login as user and implement your first bot. 
* Publishing bot to arean, creates and drops the jar inside `dist/bot/` directory.


## License

Scalatron is licensed under the Creative Commons Attribution 3.0 Unported License. The documentation, tutorial and source code are intended as a community resource and you can basically use, copy and improve them however you want. Included works are subject to their respective licenses. 
