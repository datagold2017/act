/*************************************************************************
*                                                                        *
*  This file is part of the 20n/act project.                             *
*  20n/act enables DNA prediction for synthetic biology/bioengineering.  *
*  Copyright (C) 2017 20n Labs, Inc.                                     *
*                                                                        *
*  Please direct all queries to act@20n.com.                             *
*                                                                        *
*  This program is free software: you can redistribute it and/or modify  *
*  it under the terms of the GNU General Public License as published by  *
*  the Free Software Foundation, either version 3 of the License, or     *
*  (at your option) any later version.                                   *
*                                                                        *
*  This program is distributed in the hope that it will be useful,       *
*  but WITHOUT ANY WARRANTY; without even the implied warranty of        *
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
*  GNU General Public License for more details.                          *
*                                                                        *
*  You should have received a copy of the GNU General Public License     *
*  along with this program.  If not, see <http://www.gnu.org/licenses/>. *
*                                                                        *
*************************************************************************/

package com.act.lcms.v2

import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConversions._

class LcmsElementTest extends FlatSpec with Matchers {

  val C = new LcmsElement("C")
  val H = new LcmsElement("H")
  val N = new LcmsElement("N")
  val O = new LcmsElement("O")
  val P = new LcmsElement("P")
  val S = new LcmsElement("S")

  val commonElements = List(C, H, N, O, P, S)

  "LcmsElement" should "compute the correct atomic number and mass for basic elements" in {
    val expectedMasses = Map(
      C -> 12.000000,
      H -> 1.007825,
      O -> 15.994915,
      N -> 14.003074,
      P -> 30.973762,
      S -> 31.972071
    )
    val expectedAtomicNumbers = Map(
      C -> 6,
      H -> 1,
      O -> 8,
      N -> 7,
      P -> 15,
      S -> 16
    )

    commonElements.foreach {
      case e =>
        withClue(s"For element ${e.getSymbol}") {
          e.getMass.doubleValue() should equal (expectedMasses.getOrElse(e, -1.0) +- 0.0001)
          e.getAtomicNumber shouldEqual expectedAtomicNumbers.getOrElse(e, -1)
        }
    }
  }

  "LcmsElement" should "compute correct isotopic distributions for carbon" in {

    val expectedCarbonIsotopicDistribution = List((12.0, 100.0), (13.0033, 1.0816)).sortBy(_._1)
    val computedCarbonIsotopes = C.getElementIsotopes.toList.sortBy(_.getIsotopicMass)
    val testData = expectedCarbonIsotopicDistribution zip computedCarbonIsotopes

    testData.foreach {
      case ((mass, abundance), isotope) =>
        mass should equal (isotope.getIsotopicMass.doubleValue() +- 0.0001)
        abundance should equal (isotope.getAbundance.doubleValue() +- 0.0001)
    }
  }

}
