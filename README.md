
# Apache Cassandra

Apache Cassandra is a highly-scalable partitioned row store. Rows are organized into tables with a required primary key.

- 最初由Facebook开发，后转变成了开源项目。它是一个网络社交云计算方面理想的数据库。
- 高度可扩展的高性能分布式数据库，旨在处理许多商用服务器上的大量数据，提供高可用性而没有单点故障
- 在Cassandra中，集群中的一个或多个节点充当给定数据段的副本。如果检测到某些节点响应的值过时，则Cassandra会将最新值返回给客户端。返回最新值后，Cassandra在后台执行读取修复以更新过时的值。
- Cassandra是一个混合型的非关系的数据库，类似于Google的BigTable。Cassandra的主要特点就是它不是一个数据库，而是由一堆数据库节点共同构成的一个分布式网络服务，对Cassandra 的一个写操作,会被复制到其它节点上去，对Cassandra的读操作，也会被路由到某个节点上面去读取。对于一个Cassandra群集来说，扩展性能是比较简单的事情，只管在群集里面添加节点就可以了。

- 突出特点
  - 模式灵活,使用Cassandra，像文档存储，你不必提前解决记录中的字段。你可以在系统运行时随意的添加或移除字段。这是一个惊人的效率提升，特别是在大型部署上。
  - 真正的可扩展性,Cassandra是纯粹意义上的水平扩展。为给集群添加更多容量，可以指向另一台计算机。你不必重启任何进程，改变应用查询，或手动迁移任何数据。
  - 多数据中心识别,你可以调整你的节点布局来避免某一个数据中心起火，一个备用的数据中心将至少有每条记录的完全复制。

- 其它功能
  - 范围查询,如果你不喜欢全部的键值查询，则可以设置键的范围来查询。
  - 列表数据结构,在混合模式可以将超级列添加到5维。对于每个用户的索引，这是非常方便的。
  - 分布式写操作,有可以在任何地方任何时间集中读或写任何数据。并且不会有任何单点失败。

- TCP port, for commands and data
- For security reasons, you should not expose this port to the internet.  Firewall it if needed
- storage_port: 7000

## 1 Starting a Cassandra instance is simple:
```bash
$ docker run --name some-cassandra --network some-network -d cassandra:tag
... where some-cassandra is the name you want to assign to your container and tag is the tag specifying the Cassandra version you want. See the list above for relevant tags.
```

## 2. Make a cluster
```bash
Using the environment variables documented below, there are two cluster scenarios: instances on the same machine and instances on separate machines. For the same machine, start the instance as described above. To start other instances, just tell each new node where the first is.

$ docker run --name some-cassandra2 -d --network some-network -e CASSANDRA_SEEDS=some-cassandra cassandra:tag
For separate machines (ie, two VMs on a cloud provider), you need to tell Cassandra what IP address to advertise to the other nodes (since the address of the container is behind the docker bridge).

Assuming the first machine's IP address is 10.42.42.42 and the second's is 10.43.43.43, start the first with exposed gossip port:

$ docker run --name some-cassandra -d -e CASSANDRA_BROADCAST_ADDRESS=10.42.42.42 -p 7000:7000 cassandra:tag
Then start a Cassandra container on the second machine, with the exposed gossip port and seed pointing to the first machine:

$ docker run --name some-cassandra -d -e CASSANDRA_BROADCAST_ADDRESS=10.43.43.43 -p 7000:7000 -e CASSANDRA_SEEDS=10.42.42.42 cassandra:tag
```


## 3. Connect to Cassandra from cqlsh

- The following command starts another Cassandra container instance and runs cqlsh (Cassandra Query Language Shell) against your original Cassandra container, allowing you to execute CQL statements against your database instance:

```bash
$ sudo docker run -it --network cassandra_cassandranet --rm cassandra:5.0.4 cqlsh onecassandra
$ sudo docker run -it --rm cassandra:5.0.4 cqlsh 192.168.2.202
```

## 4. 操作命令

```bash
cqlsh>
cqlsh> help
cqlsh> DESC KEYSPACES;
cqlsh> use system_schema;
cqlsh> desc tables;
cqlsh> select * from keyspaces;
cqlsh>
```



## 5 参考文档

- docker部署 https://hub.docker.com/_/cassandra
- Cassandra Documentation https://cassandra.apache.org/doc/stable/
- Cassandra Query Language (CQL) https://cassandra.apache.org/doc/stable/cassandra/developing/cql/index.html
- Cassandra 命令大全 https://blog.csdn.net/qqrrjj2011/article/details/136039826
