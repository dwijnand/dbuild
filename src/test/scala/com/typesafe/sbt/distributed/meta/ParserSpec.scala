package com.typesafe.sbt.distributed
package meta

import org.specs2.mutable.Specification


object ParserSpec extends Specification {
  "meta.Parser" should {
    "parse metadata file" in {
      
      
      Parser.parseMetaString(
"""{
  scm = "foo/bar"  
  projects = [{
          name = "p1"
          organization = "o1"
          dependencies = []
    }]
}""") must equalTo(Some(Build("foo/bar", Seq(Project("p1", "o1", Seq.empty)))))
    }
  }
}