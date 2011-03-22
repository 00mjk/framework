/*
 * Copyright 2009-2010 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.liftweb 
package json 

import java.lang.reflect.{Constructor => JConstructor, Type}
import java.lang.{Integer => JavaInteger, Long => JavaLong, Short => JavaShort, Byte => JavaByte, Boolean => JavaBoolean, Double => JavaDouble, Float => JavaFloat}
import java.util.Date
import scala.reflect.Manifest

/** Function to extract values from JSON AST using case classes.
 *
 *  See: ExtractionExamples.scala
 */
object Extraction {
  import Meta._
  import Meta.Reflection._

  /** Extract a case class from JSON.
   * @see net.liftweb.json.JsonAST.JValue#extract
   * @throws MappingException is thrown if extraction fails
   */
  def extract[A](json: JValue)(implicit formats: Formats, mf: Manifest[A]): A = 
    try {
      extract0(json, mf.erasure, mf.typeArguments.map(_.erasure)).asInstanceOf[A]
    } catch {
      case e: MappingException => throw e
      case e: Exception => throw new MappingException("unknown error", e)
    }

  /** Extract a case class from JSON.
   * @see net.liftweb.json.JsonAST.JValue#extract
   */
  def extractOpt[A](json: JValue)(implicit formats: Formats, mf: Manifest[A]): Option[A] = 
    try { Some(extract(json)(formats, mf)) } catch { case _: MappingException => None }

  /** Decompose a case class into JSON.
   * <p>
   * Example:<pre>
   * case class Person(name: String, age: Int)
   * implicit val formats = net.liftweb.json.DefaultFormats
   * Extraction.decompose(Person("joe", 25)) == JObject(JField("age",JInt(25)) :: JField("name",JString("joe")) :: Nil)
   * </pre>
   */
  def decompose(a: Any)(implicit formats: Formats): JValue = {
    def prependTypeHint(clazz: Class[_], o: JObject) = 
      JField(formats.typeHintFieldName, JString(formats.typeHints.hintFor(clazz))) ++ o

    def mkObject(clazz: Class[_], fields: List[JField]) = formats.typeHints.containsHint_?(clazz) match {
      case true  => prependTypeHint(clazz, JObject(fields))
      case false => JObject(fields)
    }
 
    val serializer = formats.typeHints.serialize
    val any = a.asInstanceOf[AnyRef]
    if (formats.customSerializer(formats).isDefinedAt(a)) {
      formats.customSerializer(formats)(a)
    } else if (!serializer.isDefinedAt(a)) {
      any match {
        case null => JNull
        case x if primitive_?(x.getClass) => primitive2jvalue(x)(formats)
        case x: Map[_, _] => JObject((x map { case (k: String, v) => JField(k, decompose(v)) }).toList)
        case x: Collection[_] => JArray(x.toList map decompose)
        case x if (x.getClass.isArray) => JArray(x.asInstanceOf[Array[_]].toList map decompose)
        case x: Option[_] => x.flatMap[JValue] { y => Some(decompose(y)) }.getOrElse(JNothing)
        case x => 
          primaryConstructorArgs(x.getClass).map { case (name, _, _) =>
            val f = x.getClass.getDeclaredField(name)
            f.setAccessible(true)
            JField(unmangleName(name), decompose(f get x))
          } match {
            case args => 
              val fields = formats.fieldSerializer(x.getClass).map { serializer => 
                Reflection.fields(x.getClass).map {
                  case (n, _) => 
                    val fieldVal = Reflection.getField(x, n)
                    val s = serializer.serializer orElse Map((n, fieldVal) -> Some(n, fieldVal))
                    s((n, fieldVal)).map { case (name, value) => JField(name, decompose(value)) }
                      .getOrElse(JField(n, JNothing))
                }
              } getOrElse Nil
              val uniqueFields = fields filterNot (f => args.find(_.name == f.name).isDefined)
              mkObject(x.getClass, uniqueFields ++ args)
          }
      }
    } else prependTypeHint(any.getClass, serializer(any))
  }
  
  /** Flattens the JSON to a key/value map.
   */
  def flatten(json: JValue): Map[String, String] = {
    def escapePath(str: String) = str

    def flatten0(path: String, json: JValue): Map[String, String] = {
      json match {
        case JNothing | JNull    => Map()
        case JString(s)          => Map(path -> ("\"" + JsonAST.quote(s) + "\""))
        case JDouble(num)        => Map(path -> num.toString)
        case JInt(num)           => Map(path -> num.toString)
        case JBool(value)        => Map(path -> value.toString)
        case JField(name, value) => flatten0(path + escapePath(name), value)
        case JObject(obj)        => obj.foldLeft(Map[String, String]()) { (map, field) => map ++ flatten0(path + ".", field) }
        case JArray(arr)         => arr.length match {
          case 0 => Map(path -> "[]")
          case _ => arr.foldLeft((Map[String, String](), 0)) { 
                      (tuple, value) => (tuple._1 ++ flatten0(path + "[" + tuple._2 + "]", value), tuple._2 + 1) 
                    }._1
        }
      }
    }

    flatten0("", json)
  }

  /** Unflattens a key/value map to a JSON object.
   */
  def unflatten(map: Map[String, String]): JValue = {
    import scala.util.matching.Regex
    
    def extractValue(value: String): JValue = value.toLowerCase match {
      case ""      => JNothing
      case "null"  => JNull
      case "true"  => JBool(true)
      case "false" => JBool(false)
      case "[]"    => JArray(Nil)
      case x @ _   => 
        if (value.charAt(0).isDigit) {
          if (value.indexOf('.') == -1) JInt(BigInt(value)) 
          else JDouble(JsonParser.parseDouble(value))
        }
        else JString(JsonParser.unquote(value.substring(1)))
    }
  
    def submap(prefix: String): Map[String, String] = 
      Map(
        map.filter(t => t._1.startsWith(prefix)).map(
          t => (t._1.substring(prefix.length), t._2)
        ).toList.toArray: _*
      )
  
    val ArrayProp = new Regex("""^(\.([^\.\[]+))\[(\d+)\].*$""")
    val ArrayElem = new Regex("""^(\[(\d+)\]).*$""")
    val OtherProp = new Regex("""^(\.([^\.\[]+)).*$""")
  
    val uniquePaths = map.keys.foldLeft[Set[String]](Set()) {
      (set, key) =>
        key match {
          case ArrayProp(p, f, i) => set + p
          case OtherProp(p, f)    => set + p    
          case ArrayElem(p, i)    => set + p        
          case x @ _              => set + x
        }
    }.toList.sortWith(_ < _) // Sort is necessary to get array order right
    
    uniquePaths.foldLeft[JValue](JNothing) { (jvalue, key) => 
      jvalue.merge(key match {
        case ArrayProp(p, f, i) => JObject(List(JField(f, unflatten(submap(key)))))
        case ArrayElem(p, i)    => JArray(List(unflatten(submap(key))))
        case OtherProp(p, f)    => JObject(List(JField(f, unflatten(submap(key)))))
        case ""                 => extractValue(map(key))
      })
    }
  }

  private def extract0(json: JValue, clazz: Class[_], typeArgs: Seq[Class[_]])
                      (implicit formats: Formats): Any = {
    val mapping = 
      if (clazz == classOf[List[_]] || clazz == classOf[Set[_]] || clazz.isArray) 
        Col(clazz, mappingOf(typeArgs(0)))
      else if (clazz == classOf[Map[_, _]]) 
        Dict(mappingOf(typeArgs(1)))
      else mappingOf(clazz)
    extract0(json, mapping)
  }

  def extract(json: JValue, target: TypeInfo)(implicit formats: Formats): Any = 
    extract0(json, mappingOf(target.clazz))

  private def extract0(json: JValue, mapping: Mapping)(implicit formats: Formats): Any = {
    def newInstance(constructor: Constructor, json: JValue) = {
      def findBestConstructor = {
        if (constructor.choices.size == 1) constructor.choices.head // optimized common case
        else {
          val argNames = json match {
            case JObject(fs) => fs.map(_.name)
            case JField(name, _) => List(name)
            case x => Nil
          }
          constructor.bestMatching(argNames)
        }
      }

      def setFields(a: AnyRef, json: JValue, constructor: JConstructor[_]) = json match {
        case o: JObject =>
          formats.fieldSerializer(a.getClass).map { serializer =>
            val constructorArgNames = 
              Reflection.constructorArgs(constructor, formats.parameterNameReader).map(_._1).toSet
            val jsonFields = o.obj.map { f => 
              val JField(n, v) = (serializer.deserializer orElse Map(f -> f))(f)
              (n, (n, v))
            }.toMap

            val fieldsToSet = 
              Reflection.fields(a.getClass).filterNot(f => constructorArgNames.contains(f._1))

            fieldsToSet.foreach { case (name, typeInfo) =>
              jsonFields.get(name).foreach { case (n, v) =>
                val typeArgs = typeInfo.parameterizedType
                  .map(_.getActualTypeArguments.map(_.asInstanceOf[Class[_]]).toList)
                val value = extract0(v, typeInfo.clazz, typeArgs.getOrElse(Nil))
                Reflection.setField(a, n, value)
              }
            }
          }
          a
        case _ => a
      }

      def instantiate = {
        val c = findBestConstructor
        val jconstructor = c.constructor
        val args = c.args.map(a => build(json \ a.path, a))
        try {
          if (jconstructor.getDeclaringClass == classOf[java.lang.Object]) 
            fail("No information known about type")

          val instance = jconstructor.newInstance(args.map(_.asInstanceOf[AnyRef]).toArray: _*)
          setFields(instance.asInstanceOf[AnyRef], json, jconstructor)
        } catch {
          case e @ (_:IllegalArgumentException | _:InstantiationException) =>
            fail("Parsed JSON values do not match with class constructor\nargs=" + 
                 args.mkString(",") + "\narg types=" + args.map(a => if (a != null) 
                   a.asInstanceOf[AnyRef].getClass.getName else "null").mkString(",") + 
                 "\nconstructor=" + jconstructor)
        }
      }

      def mkWithTypeHint(typeHint: String, fields: List[JField]) = {
        val obj = JObject(fields)
        val deserializer = formats.typeHints.deserialize
        if (!deserializer.isDefinedAt(typeHint, obj)) {
          val concreteClass = formats.typeHints.classFor(typeHint) getOrElse fail("Do not know how to deserialize '" + typeHint + "'")
          build(obj, mappingOf(concreteClass))
        } else deserializer(typeHint, obj)
      }

      val custom = formats.customDeserializer(formats)
      val JsonClass = formats.typeHintFieldName
      if (custom.isDefinedAt(constructor.targetType, json)) custom(constructor.targetType, json)
      else json match {
        case JNull => null
        case JObject(JField(JsonClass, JString(t)) :: xs) => mkWithTypeHint(t, xs)
        case JField(_, JObject(JField(JsonClass, JString(t)) :: xs)) => mkWithTypeHint(t, xs)
        case _ => instantiate
      }
    }

    def newPrimitive(elementType: Class[_], elem: JValue) = convert(elem, elementType, formats)
    
    def newCollection(root: JValue, m: Mapping, constructor: Array[_] => Any) = {
      val array: Array[_] = root match {
        case JArray(arr)      => arr.map(build(_, m)).toArray
        case JNothing | JNull => Array[AnyRef]()
        case x                => fail("Expected collection but got " + x + " for root " + root + " and mapping " + m)
      }
      
      constructor(array)
    }

    def build(root: JValue, mapping: Mapping): Any = mapping match {
      case Value(targetType) => convert(root, targetType, formats)
      case c: Constructor => newInstance(c, root)
      case Cycle(targetType) => build(root, mappingOf(targetType))
      case Arg(path, m, optional) => mkValue(fieldValue(root), m, path, optional)
      case Col(c, m) =>
        if (c == classOf[List[_]]) newCollection(root, m, a => List(a: _*))
        else if (c == classOf[Set[_]]) newCollection(root, m, a => Set(a: _*))
        else if (c.isArray) newCollection(root, m, mkTypedArray(c))
        else fail("Expected collection but got " + m + " for class " + c)
      case Dict(m) => root match {
        case JObject(xs) => Map(xs.map(x => (x.name, build(x.value, m))): _*)
        case x => fail("Expected object but got " + x)
      }
    }
    
    def mkTypedArray(c: Class[_])(a: Array[_]) = {
      import java.lang.reflect.Array.{newInstance => newArray}
      
      a.foldLeft((newArray(c.getComponentType, a.length), 0)) { (tuple, e) => {
        java.lang.reflect.Array.set(tuple._1, tuple._2, e); (tuple._1, tuple._2 + 1)
      }}._1
    }

    def mkList(root: JValue, m: Mapping) = root match {
      case JArray(arr) => arr.map(build(_, m))
      case JNothing | JNull => Nil
      case x => fail("Expected array but got " + x)
    }

    def mkValue(root: JValue, mapping: Mapping, path: String, optional: Boolean) = 
      if (optional && root == JNothing) None
      else {
        try {
          val x = build(root, mapping)
          if (optional) {
            if (x == null) None else Some(x) 
          } else x
        } catch { 
          case MappingException(msg, _) => 
            if (optional) None else fail("No usable value for " + path + "\n" + msg)
        }
      }

    def fieldValue(json: JValue): JValue = json match {
      case JField(_, value) => value
      case x => x
    }

    build(json, mapping)
  }

  private def convert(json: JValue, targetType: Class[_], formats: Formats): Any = json match {
    case JInt(x) if (targetType == classOf[Int]) => x.intValue
    case JInt(x) if (targetType == classOf[JavaInteger]) => new JavaInteger(x.intValue)
    case JInt(x) if (targetType == classOf[BigInt]) => x
    case JInt(x) if (targetType == classOf[Long]) => x.longValue
    case JInt(x) if (targetType == classOf[JavaLong]) => new JavaLong(x.longValue)
    case JInt(x) if (targetType == classOf[Double]) => x.doubleValue
    case JInt(x) if (targetType == classOf[JavaDouble]) => new JavaDouble(x.doubleValue)
    case JInt(x) if (targetType == classOf[Float]) => x.floatValue
    case JInt(x) if (targetType == classOf[JavaFloat]) => new JavaFloat(x.floatValue)
    case JInt(x) if (targetType == classOf[Short]) => x.shortValue
    case JInt(x) if (targetType == classOf[JavaShort]) => new JavaShort(x.shortValue)
    case JInt(x) if (targetType == classOf[Byte]) => x.byteValue
    case JInt(x) if (targetType == classOf[JavaByte]) => new JavaByte(x.byteValue)
    case JInt(x) if (targetType == classOf[String]) => x.toString
    case JInt(x) if (targetType == classOf[Number]) => x.longValue
    case JDouble(x) if (targetType == classOf[Double]) => x
    case JDouble(x) if (targetType == classOf[JavaDouble]) => new JavaDouble(x)
    case JDouble(x) if (targetType == classOf[Float]) => x.floatValue
    case JDouble(x) if (targetType == classOf[JavaFloat]) => new JavaFloat(x.floatValue)
    case JDouble(x) if (targetType == classOf[String]) => x.toString
    case JDouble(x) if (targetType == classOf[Int]) => x.intValue
    case JDouble(x) if (targetType == classOf[Long]) => x.longValue
    case JDouble(x) if (targetType == classOf[Number]) => x
    case JString(s) if (targetType == classOf[String]) => s
    case JString(s) if (targetType == classOf[Symbol]) => Symbol(s)
    case JString(s) if (targetType == classOf[Date]) => formats.dateFormat.parse(s).getOrElse(fail("Invalid date '" + s + "'"))
    case JBool(x) if (targetType == classOf[Boolean]) => x
    case JBool(x) if (targetType == classOf[JavaBoolean]) => new JavaBoolean(x)
    case j: JValue if (targetType == classOf[JValue]) => j
    case j: JObject if (targetType == classOf[JObject]) => j
    case j: JArray if (targetType == classOf[JArray]) => j
    case JNull => null
    case JNothing => fail("Did not find value which can be converted into " + targetType.getName)
    case JField(_, x) => convert(x, targetType, formats)
    case _ => 
      val custom = formats.customDeserializer(formats)
      val typeInfo = TypeInfo(targetType, None)
      if (custom.isDefinedAt(typeInfo, json)) custom(typeInfo, json)
      else fail("Do not know how to convert " + json + " into " + targetType)
  }
}

