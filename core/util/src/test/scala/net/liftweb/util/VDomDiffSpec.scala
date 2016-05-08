package net.liftweb.util

import org.specs2.mutable.Specification

import VDom._
import VDomHelpers._
import VNode.{text => txt}

object VDomDiffSpec extends Specification {
  "VDom.diff() Specification".title

  "VDom.diff" should {
    import VDom.diff

    "find an appended element" in {
      val before =
        <div>
          <hr/>
          <ul>
            <li>Message 1</li>
            <li>Message 2</li>
          </ul>
        </div>

      val after =
        <div>
          <hr/>
          <ul>
            <li>Message 1</li>
            <li>Message 2</li>
            <li>Message 3</li>
          </ul>
        </div>

      val expected =
        node(
          node(),
          node(
            node(),
            node()
          ).withPatches(VNodeInsert(2, VNode("li", Map(), List(txt("Message 3")))))
        )

      diff(before, after) must_== expected
    }

    "find an appended child element of an element with an id" in {
      val before =
        <div>
          <hr/>
          <ul id="my-messages">
            <li>Message 1</li>
            <li>Message 2</li>
          </ul>
        </div>

      val after =
        <div>
          <hr/>
          <ul id="my-messages">
            <li>Message 1</li>
            <li>Message 2</li>
            <li>Message 3</li>
          </ul>
        </div>

      val expected =
        node(
          node(),
          node(
            node(),
            node()
          ).withPatches(VNodeInsert(2, VNode("li", Map(), List(txt("Message 3")))))
        )

      diff(before, after) must_== expected
    }

    "find an inserted element" in {
      val before =
        <div>
          <hr/>
          <ul>
            <li>Message 1</li>
            <li>Message 2</li>
          </ul>
        </div>

      val after =
        <div>
          <hr/>
          <ul>
            <li>Message 1</li>
            <li>Message 3</li>
            <li>Message 2</li>
          </ul>
        </div>

      val expected =
        node(
          node(),
          node(
            node(),
            node()
          ).withPatches(VNodeInsert(1, VNode("li", Map(), List(txt("Message 3")))))
        )

      diff(before, after) must_== expected
    }

    "find an removed element" in {
      val before =
        <div>
          <hr/>
          <ul>
            <li>Message 1</li>
            <li>Message 2</li>
          </ul>
        </div>

      val after =
        <div>
          <hr/>
          <ul>
            <li>Message 2</li>
          </ul>
        </div>

      val expected =
        node(
          node(),
          node(
            node()
          ).withPatches(VNodeDelete(0))
        )

      diff(before, after) must_== expected
    }

    "find an element replaced by specifying a delete then an insert" in {
      val before =
        <div>
          <hr/>
          <ul>
            <li>Message 1</li>
            <li>Message 2</li>
          </ul>
        </div>

      val after =
        <div>
          <hr/>
          <ul>
            <li>Message 1</li>
            <li>Message 3</li>
          </ul>
        </div>

      val expected =
        node(
          node(),
          node(
            node()
          ).withPatches(VNodeDelete(1), VNodeInsert(1, VNode("li", Map(), List(txt("Message 3")))))
        )

      diff(before, after) must_== expected
    }

    "find reordered elements" in {
      val before =
        <div>
          <hr/>
          <ul>
            <li>Message 1</li>
            <li>Message 2</li>
            <li>Message 3</li>
            <li>Message 4</li>
          </ul>
        </div>

      val after =
        <div>
          <hr/>
          <ul>
            <li>Message 2</li>
            <li>Message 4</li>
            <li>Message 3</li>
            <li>Message 1</li>
          </ul>
        </div>

      val expected =
        node(
          node(),
          node(
            node(),
            node()
          ).withPatches(VNodeReorder(0, 3, 1))
        )

      diff(before, after) must_== expected
    }.pendingUntilFixed

  }
}
