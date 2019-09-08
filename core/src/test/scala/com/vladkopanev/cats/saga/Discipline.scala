package com.vladkopanev.cats.saga

import org.scalatest.FunSuiteLike
import org.scalatest.prop.Checkers
import org.typelevel.discipline.Laws

/*
* Ported from org.typelevel.discipline: 0.8 which was a dependency of cats-laws:1.1.0 but being removed from cats-laws 2.0.0-RC3
* to force library release for scala 2.13 without waiting for scalatest to publish their 2.13 artifacts
*  */
trait Discipline extends Checkers { self: FunSuiteLike =>

  def checkAll(name: String, ruleSet: Laws#RuleSet) {
    for ((id, prop) <- ruleSet.all.properties)
      test(name + "." + id) {
        check(prop)
      }
  }

}