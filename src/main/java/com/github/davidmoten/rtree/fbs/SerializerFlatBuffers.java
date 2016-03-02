package com.github.davidmoten.rtree.fbs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.github.davidmoten.guavamini.Optional;
import com.github.davidmoten.rtree.Context;
import com.github.davidmoten.rtree.Entry;
import com.github.davidmoten.rtree.Leaf;
import com.github.davidmoten.rtree.Node;
import com.github.davidmoten.rtree.NonLeaf;
import com.github.davidmoten.rtree.RTree;
import com.github.davidmoten.rtree.SelectorRStar;
import com.github.davidmoten.rtree.SerializerHelper;
import com.github.davidmoten.rtree.SplitterRStar;
import com.github.davidmoten.rtree.InternalStructure;
import com.github.davidmoten.rtree.fbs.generated.Box_;
import com.github.davidmoten.rtree.fbs.generated.Context_;
import com.github.davidmoten.rtree.fbs.generated.Node_;
import com.github.davidmoten.rtree.fbs.generated.Tree_;
import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.geometry.Rectangle;
import com.github.davidmoten.rtree.internal.LeafDefault;
import com.github.davidmoten.rtree.internal.NonLeafDefault;
import com.google.flatbuffers.FlatBufferBuilder;

import rx.functions.Func1;

public final class SerializerFlatBuffers<T, S extends Geometry> {

    private final FactoryFlatBuffers<T, S> factory;

    private SerializerFlatBuffers(Func1<T, byte[]> serializer, Func1<byte[], T> deserializer) {
        this.factory = new FactoryFlatBuffers<T, S>(serializer, deserializer);
    }

    public static <T, S extends Geometry> SerializerFlatBuffers<T, S> create(
            Func1<T, byte[]> serializer, Func1<byte[], T> deserializer) {
        return new SerializerFlatBuffers<T, S>(serializer, deserializer);
    }

    public void serialize(RTree<T, S> tree, OutputStream os) throws IOException {
        FlatBufferBuilder builder = new FlatBufferBuilder();
        int n = addNode(tree.root().get(), builder, factory.serializer());
        Rectangle mbb = tree.root().get().geometry().mbr();
        int b = Box_.createBox_(builder, mbb.x1(), mbb.y1(), mbb.x2(), mbb.y2());
        Context_.startContext_(builder);
        Context_.addBounds(builder, b);
        Context_.addMinChildren(builder, tree.context().minChildren());
        Context_.addMaxChildren(builder, tree.context().maxChildren());
        int c = Context_.endContext_(builder);

        int t = Tree_.createTree_(builder, c, n, tree.size());
        Tree_.finishTree_Buffer(builder, t);
        ByteBuffer bb = builder.dataBuffer();
        os.write(bb.array(), bb.position(), bb.remaining());
    }

    private static <T, S extends Geometry> int addNode(Node<T, S> node, FlatBufferBuilder builder,
            Func1<T, byte[]> serializer) {
        if (node instanceof Leaf) {
            Leaf<T, S> leaf = (Leaf<T, S>) node;
            return FlatBuffersHelper.addEntries(leaf.entries(), builder, serializer);
        } else {
            NonLeaf<T, S> nonLeaf = (NonLeaf<T, S>) node;
            int[] nodes = new int[nonLeaf.count()];
            for (int i = 0; i < nonLeaf.count(); i++) {
                Node<T, S> child = nonLeaf.children().get(i);
                nodes[i] = addNode(child, builder, serializer);
            }
            int ch = Node_.createChildrenVector(builder, nodes);
            Node_.startNode_(builder);
            Node_.addChildren(builder, ch);
            Rectangle mbb = nonLeaf.geometry().mbr();
            int b = Box_.createBox_(builder, mbb.x1(), mbb.y1(), mbb.x2(), mbb.y2());
            Node_.addMbb(builder, b);
            return Node_.endNode_(builder);
        }
    }

    public RTree<T, S> deserialize(long sizeBytes, InputStream is, InternalStructure structure)
            throws IOException {
        byte[] bytes = readFully(is, (int) sizeBytes);
        Tree_ t = Tree_.getRootAsTree_(ByteBuffer.wrap(bytes));
        Node_ node = t.root();
        Context<T, S> context = new Context<T, S>(t.context().minChildren(),
                t.context().maxChildren(), new SelectorRStar(), new SplitterRStar(), factory);
        final Node<T, S> root;
        if (structure == InternalStructure.FLATBUFFERS_SINGLE_ARRAY) {
            if (node.childrenLength() > 0) {
                root = new NonLeafFlatBuffersStatic<T, S>(node, context, factory.deserializer());
            } else {
                List<Entry<T, S>> entries = FlatBuffersHelper.createEntries(node,
                        factory.deserializer());
                root = new LeafDefault<T, S>(entries, context);
            }
        } else {
            root = toNodeDefault(node, context, factory.deserializer());
        }
        return SerializerHelper.create(Optional.of(root), (int) t.size(), context);
    }

    private static <T, S extends Geometry> Node<T, S> toNodeDefault(Node_ node,
            Context<T, S> context, Func1<byte[], T> deserializer) {
        if (node.childrenLength() > 0) {
            List<Node<T, S>> children = new ArrayList<Node<T, S>>(node.childrenLength());
            for (int i = 0; i < node.childrenLength(); i++) {
                children.add(toNodeDefault(node.children(i), context, deserializer));
            }
            return new NonLeafDefault<T, S>(children, context);
        } else {
            List<Entry<T, S>> entries = FlatBuffersHelper.createEntries(node, deserializer);
            return new LeafDefault<T, S>(entries, context);
        }
    }

    private static byte[] readFully(InputStream is, int numBytes) throws IOException {
        byte[] b = new byte[numBytes];
        int n = is.read(b);
        if (n != numBytes)
            throw new RuntimeException("unexpected");
        return b;
    }

}
