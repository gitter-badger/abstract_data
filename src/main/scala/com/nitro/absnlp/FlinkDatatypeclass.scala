package com.nitro.absnlp

import org.apache.flink.api.common.ExecutionConfig
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.common.typeutils.TypeSerializer
import org.apache.flink.api.java.typeutils.runtime.kryo.KryoSerializer
import org.apache.flink.api.scala.DataSet
import org.apache.flink.api.scala.typeutils.{ CaseClassSerializer, CaseClassTypeInfo }

import scala.language.higherKinds
import scala.reflect.ClassTag
import scala.util.Try

private[absnlp] object FlinkHelper extends Serializable {

  private[absnlp] val productClass: Class[Product] =
    classOf[Product]

  private[absnlp] def countFields(c: Class[_]): Int = {

    val fields = c.getFields
    if (fields.isEmpty)
      1

    else
      fields.foldLeft(0) {
        case (result, field) =>
          result + countFields(field.getClass)
      }
  }

  def mapCombine[B, A](m1: Map[B, Iterator[A]], m2: Map[B, Iterator[A]]): Map[B, Iterator[A]] = {

    val (larger, smaller) =
      if (m1.size > m2.size)
        (m1, m2)
      else
        (m2, m1)

    smaller.foldLeft(larger) {
      case (m, (key, value)) =>
        if (m.contains(key))
          (m - key) + (key -> (m(key) ++ value))
        else
          m + (key -> value)
    }
  }

  private[absnlp] val emptyTypeInfoList: List[TypeInformation[_]] =
    List.empty[TypeInformation[_]]

  private[absnlp] val emptyUnitSeq: Seq[Unit] =
    Seq.empty[Unit]

  val unitTypeInformation: TypeInformation[Unit] =
    makeTypeInformation(ClassTag(classOf[Unit]))

  def makeTypeInformation[A: ClassTag]: TypeInformation[A] = {

    val ct = implicitly[ClassTag[A]]

    new TypeInformation[A] {

      override lazy val isBasicType: Boolean =
        ct.runtimeClass.isPrimitive || ct.equals(ClassTag(classOf[String]))

      override lazy val isTupleType: Boolean =
        productClass.isAssignableFrom(ct.runtimeClass)

      override lazy val getArity: Int =
        ct.runtimeClass.getFields.length

      override lazy val getTotalFields: Int =
        countFields(ct.runtimeClass)

      override lazy val getTypeClass: Class[A] =
        ct.runtimeClass.asInstanceOf[Class[A]]

      override lazy val getGenericParameters: java.util.List[TypeInformation[_]] = {

        import scala.collection.JavaConversions._

        val tVars = ct.getClass.getTypeParameters
        if (tVars.isEmpty)
          emptyTypeInfoList

        else
          tVars
            .map { typeVariable =>
              val genericClass = typeVariable.getGenericDeclaration
              makeTypeInformation(ClassTag(genericClass))
            }
            .toList
      }

      override lazy val isKeyType: Boolean =
        isBasicType

      override lazy val isSortKeyType: Boolean =
        isKeyType

      override def createSerializer(config: ExecutionConfig): TypeSerializer[A] =
        new KryoSerializer[A](getTypeClass, config)
    }
  }

}

case object FlinkDatatypeclass extends DataTypeclass[DataSet] {

  //  override def empty[A]: DataSet[A] =
  //    ???

  /** Transform a dataset by applying f to each element. */
  override def map[A, B: ClassTag](data: DataSet[A])(f: (A) => B): DataSet[B] = {
    implicit val ti = FlinkHelper.makeTypeInformation[B]
    data.map(f)
  }

  override def mapParition[A, B: ClassTag](d: DataSet[A])(f: Iterable[A] => Iterable[B]): DataSet[B] = {
    implicit val ti = FlinkHelper.makeTypeInformation[B]
    d.mapPartition { partition =>
      f(partition.toIterable)
    }
  }

  /** Apply a side-effecting function to each element. */
  override def foreach[A](d: DataSet[A])(f: A => Any): Unit = {
    // ignore this map operation's return type
    implicit val ti = FlinkHelper.unitTypeInformation
    d.mapPartition { partition =>
      partition.foreach(f)
      FlinkHelper.emptyUnitSeq
    }
    ()
  }

  override def foreachPartition[A](d: DataSet[A])(f: Iterable[A] => Any): Unit = {
    // ignore this map operation's return type
    implicit val ti = FlinkHelper.unitTypeInformation
    d.mapPartition { partition =>
      f(partition.toIterable)
      FlinkHelper.emptyUnitSeq
    }
    ()
  }

  override def filter[A](d: DataSet[A])(f: A => Boolean): DataSet[A] =
    d.filter(f)

  override def aggregate[A, B: ClassTag](data: DataSet[A])(zero: B)(seqOp: (B, A) => B, combOp: (B, B) => B): B = {
    implicit val ti = FlinkHelper.makeTypeInformation[B]
    data
      .mapPartition { partition =>
        Seq(partition.foldLeft(zero)(seqOp))
      }
      .reduce(combOp)
      .collect()
      .reduce(combOp)
  }

  /** Sort the dataset using a function f that evaluates each element to an orderable type */
  override def sortBy[A, B: ClassTag](d: DataSet[A])(f: (A) ⇒ B)(implicit ord: math.Ordering[B]): DataSet[A] =
    ???

  /** Construct a DataSet for the first k elements of a dataset. Will load into main mem. */
  override def take[A](d: DataSet[A])(k: Int): Traversable[A] =
    d.first(k).collect()

  override def headOption[A](d: DataSet[A]): Option[A] =
    Try(d.first(1).collect().head).toOption

  /** Load all elements of the dataset into an array in main memory. */
  override def toSeq[A](d: DataSet[A]): Seq[A] =
    d.collect()

  override def flatMap[A, B: ClassTag](d: DataSet[A])(f: A => TraversableOnce[B]): DataSet[B] = {
    implicit val ti = FlinkHelper.makeTypeInformation[B]
    d.flatMap(f)
  }

  override def flatten[A, B: ClassTag](d: DataSet[A])(implicit asDataSet: A => TraversableOnce[B]): DataSet[B] =
    flatMap(d)(asDataSet)

  override def groupBy[A, B: ClassTag](data: DataSet[A])(f: A => B): DataSet[(B, Iterator[A])] = {

    val reducedToMaps = {

      implicit val ti: TypeInformation[scala.collection.immutable.Map[B, Iterator[A]]] =
        FlinkHelper.makeTypeInformation(ClassTag(classOf[Map[B, Iterator[A]]]))

      data
        .mapPartition { partition =>
          Seq(
            partition.toIterable
              .groupBy(f)
              .map {
                case (key, values) => (key, values.toIterator)
              }
          )
        }
        .reduce(FlinkHelper.mapCombine[B, A] _)
    }

    implicit val ti: TypeInformation[(B, Iterator[A])] =
      FlinkHelper.makeTypeInformation(ClassTag(classOf[(B, Iterator[A])]))

    reducedToMaps
      .flatMap(_.toSeq)
  }

  /** This has type A as opposed to B >: A due to the RDD limitations */
  override def reduce[A](d: DataSet[A])(op: (A, A) => A): A =
    d.reduce(op).collect().head

  override def toMap[A, T, U](data: DataSet[A])(implicit ev: A <:< (T, U)): Map[T, U] = {

    implicit val ti: TypeInformation[(T, U)] =
      FlinkHelper.makeTypeInformation(ClassTag(classOf[(T, U)]))

    aggregate(data)(Map.empty[T, U])(
      {
        case (m, a) =>
          val (t, u) = ev(a)
          m + (t -> u)
      },
      {
        case (m1, m2) =>
          val (larger, smaller) =
            if (m1.size > m2.size)
              (m1, m2)
            else
              (m2, m1)

          smaller.foldLeft(larger) {
            case (m, (key, value)) =>
              if (!m.contains(key))
                m + (key -> value)
              else
                m
          }
      }
    )
  }

  override def size[A](d: DataSet[A]): Long =
    d.count()

  override def isEmpty[A](d: DataSet[A]): Boolean =
    size(d) == 0

  override def sum[N: ClassTag: Numeric](d: DataSet[N]): N = {
    val add = implicitly[Numeric[N]].plus _
    implicit val ti = FlinkHelper.makeTypeInformation[N]
    aggregate(d)(implicitly[Numeric[N]].zero)(add, add)
  }

  override def zip[A, B: ClassTag](d: DataSet[A])(that: DataSet[B]): DataSet[(A, B)] =
    ???

  override def zipWithIndex[A](d: DataSet[A]): DataSet[(A, Long)] =
    ???

}