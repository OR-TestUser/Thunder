package network.thunder.core.database;

import network.thunder.core.communication.NodeKey;
import network.thunder.core.communication.layer.DIRECTION;
import network.thunder.core.communication.layer.MessageWrapper;
import network.thunder.core.communication.layer.high.AckableMessage;
import network.thunder.core.communication.layer.high.Channel;
import network.thunder.core.communication.layer.high.NumberedMessage;
import network.thunder.core.communication.layer.high.RevocationHash;
import network.thunder.core.communication.layer.high.payments.LNOnionHelper;
import network.thunder.core.communication.layer.high.payments.LNOnionHelperImpl;
import network.thunder.core.communication.layer.high.payments.PaymentData;
import network.thunder.core.communication.layer.high.payments.PaymentSecret;
import network.thunder.core.communication.layer.high.payments.messages.ChannelUpdate;
import network.thunder.core.communication.layer.high.payments.messages.PeeledOnion;
import network.thunder.core.communication.layer.middle.broadcasting.types.ChannelStatusObject;
import network.thunder.core.communication.layer.middle.broadcasting.types.P2PDataObject;
import network.thunder.core.communication.layer.middle.broadcasting.types.PubkeyChannelObject;
import network.thunder.core.communication.layer.middle.broadcasting.types.PubkeyIPObject;
import network.thunder.core.database.hibernate.*;
import network.thunder.core.database.objects.PaymentStatus;
import network.thunder.core.database.objects.PaymentWrapper;
import network.thunder.core.etc.Constants;
import network.thunder.core.etc.Tools;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static network.thunder.core.communication.layer.high.Channel.Phase.OPEN;
import static network.thunder.core.database.objects.PaymentStatus.*;

public class HibernateHandler implements DBHandler {
    public Map<Integer, List<P2PDataObject>> fragmentToListMap = new HashMap<>();

    public final List<P2PDataObject> totalList = Collections.synchronizedList(new ArrayList<>());
    private final Collection<ByteBuffer> knownObjects = new HashSet<>();

    public Map<Sha256Hash, List<RevocationHash>> revocationHashListTheir = new ConcurrentHashMap<>();

    final List<PaymentWrapper> payments = Collections.synchronizedList(new ArrayList<>());

    List<PaymentSecret> secrets = new ArrayList<>();

    Map<NodeKey, List<MessageWrapper>> messageList = new ConcurrentHashMap<>();
    Map<NodeKey, Long> messageCountList = new ConcurrentHashMap<>();
    Map<NodeKey, Map<Long, NumberedMessage>> sentMessages = new ConcurrentHashMap<>();
    Map<NodeKey, Map<Long, Long>> linkedMessageMap = new ConcurrentHashMap<>();
    Map<NodeKey, List<AckableMessage>> unAckedMessageMap = new ConcurrentHashMap<>();

    LNOnionHelper onionHelper = new LNOnionHelperImpl();
    private SessionFactory sessionFactory;

    public HibernateHandler () {
        Properties properties = new Properties();
        properties.put("hibernate.connection.driver_class", "org.h2.Driver");
        properties.put("hibernate.connection.url", "jdbc:h2:mem:");
        properties.put("hibernate.connection.pool_size", 1);
        properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        properties.put("hibernate.cache.provider_class", "org.hibernate.cache.internal.NoCacheProvider");
        properties.put("hibernate.hbm2ddl.auto", "create-drop");
        properties.put("hibernate.show_sql", "true");

        Configuration configuration = new Configuration();

        configuration
                .addAnnotatedClass(ChannelEntity.class)
                .addAnnotatedClass(HibernateSignature.class)
                .addAnnotatedClass(PaymentDataEntity.class)
                .addAnnotatedClass(PubkeyChannelObjectEntity.class)
                .addAnnotatedClass(PubkeyIPObjectEntity.class)
                .addProperties(properties);

        sessionFactory = configuration.buildSessionFactory();

        for (int i = 0; i < P2PDataObject.NUMBER_OF_FRAGMENTS + 1; i++) {
            fragmentToListMap.put(i, Collections.synchronizedList(new ArrayList<>()));
        }
    }

    @Override
    public void insertIPObjects (List<P2PDataObject> ipList) {
        syncDatalist(ipList);
    }

    @Override
    public List<PubkeyIPObject> getIPObjects () {
        Session session = sessionFactory.openSession();
        Transaction tx = session.beginTransaction();
        List<PubkeyIPObject> pubkeyIPObjects = session
                .createQuery("from PubkeyIPObject", PubkeyIPObjectEntity.class)
                .list().stream()
                .map(PubkeyIPObjectEntity::toPubkeyIPObject)
                .collect(Collectors.toList());
        tx.commit();
        session.close();
        return pubkeyIPObjects;
    }

    @Override
    public P2PDataObject getP2PDataObjectByHash (byte[] hash) {
        synchronized (totalList) {
            for (P2PDataObject object : totalList) {
                if (Arrays.equals(object.getHash(), hash)) {
                    return object;
                }
            }
        }
        return null;
    }

    @Override
    public PubkeyIPObject getIPObject (byte[] nodeKey) {
        Session session = sessionFactory.openSession();
        Transaction tx = session.beginTransaction();
        PubkeyIPObject pubkeyIPObject = session
                .createQuery(
                        "from PubkeyIPObject " +
                                "where pubkey = :pubkey",
                        PubkeyIPObjectEntity.class)
                .setParameter("pubkey", nodeKey)
                .list().stream()
                .map(PubkeyIPObjectEntity::toPubkeyIPObject)
                .findFirst().orElse(null);
        tx.commit();
        session.close();
        return pubkeyIPObject;
    }

    @Override
    public void invalidateP2PObject (P2PDataObject ipObject) {
        //TODO with a real database, we rather want to invalidate them, rather then just deleting these..
        synchronized (totalList) {
            Session session = sessionFactory.openSession();
            Transaction tx = session.beginTransaction();
            totalList.remove(ipObject);
            if (ipObject instanceof PubkeyIPObject) {
                session
                        .createQuery(
                                "delete PubkeyIPObject " +
                                        "where hostname = :hostname and port = :port")
                        .setParameter("hostname", ((PubkeyIPObject) ipObject).hostname)
                        .setParameter("port", ((PubkeyIPObject) ipObject).port)
                        .executeUpdate();
            }
            tx.commit();
            session.close();
        }
        //TODO implement other objects
    }

    @Override
    public synchronized void syncDatalist (List<P2PDataObject> dataList) {
        Iterator<P2PDataObject> iterator1 = dataList.iterator();

        while (iterator1.hasNext()) {
            boolean deleted = false;
            P2PDataObject object1 = iterator1.next();

            if (knownObjects.contains(ByteBuffer.wrap(object1.getHash()))) {
                iterator1.remove();
                continue;
            }

            synchronized (totalList) {
                Session session = sessionFactory.openSession();
                Transaction tx = session.beginTransaction();
                Iterator<P2PDataObject> iterator2 = totalList.iterator();
                while (iterator2.hasNext() && !deleted) {
                    P2PDataObject object2 = iterator2.next();
                    if (object1.isSimilarObject(object2)) {

                        if (object1.getTimestamp() <= object2.getTimestamp()) {
                            iterator1.remove();
                        } else {
                            iterator2.remove();
                            for (int i = 0; i < P2PDataObject.NUMBER_OF_FRAGMENTS + 1; i++) {
                                fragmentToListMap.get(i).remove(object2);
                            }
                            if (object2 instanceof PubkeyIPObject) {
                                session
                                        .createQuery(
                                                "delete PubkeyIPObject " +
                                                        "where hostname = :hostname and port = :port")
                                        .setParameter("hostname", ((PubkeyIPObject) object2).hostname)
                                        .setParameter("port", ((PubkeyIPObject) object2).port)
                                        .executeUpdate();
                            }
                            if (object2 instanceof PubkeyChannelObject) {
                                session
                                        .createQuery(
                                                "delete PubkeyChannelObject " +
                                                        "where (pubkeyA = :pubkeyA and pubkeyB = :pubkeyB) " +
                                                        "or (pubkeyA = :pubkeyB and pubkeyB = :pubkeyA)")
                                        .setParameter("pubkeyA", ((PubkeyChannelObject) object2).pubkeyB)
                                        .setParameter("pubkeyB", ((PubkeyChannelObject) object2).pubkeyB)
                                        .executeUpdate();
                            }
                            if (object2 instanceof ChannelStatusObject) {
                                session
                                        .createQuery(
                                                "delete ChannelStatusObject " +
                                                        "where pubkeyA = :pubkeyA and pubkeyB = :pubkeyB")
                                        .setParameter("pubkeyA", ((ChannelStatusObject) object2).pubkeyA)
                                        .setParameter("pubkeyB", ((ChannelStatusObject) object2).pubkeyB)
                                        .executeUpdate();
                            }
                        }
                        deleted = true;
                    }
                }
                tx.commit();
                session.close();
            }
        }

        synchronized (totalList) {
            Session session = sessionFactory.openSession();
            Transaction tx = session.beginTransaction();
            Iterator<P2PDataObject> iterator2 = totalList.iterator();
            while (iterator2.hasNext()) {
                P2PDataObject object2 = iterator2.next();
                int timestamp = object2.getTimestamp();
                if (timestamp != 0 && Tools.currentTime() - timestamp > P2PDataObject.MAXIMUM_AGE_SYNC_DATA) {
                    iterator2.remove();
                    for (int i = 0; i < P2PDataObject.NUMBER_OF_FRAGMENTS + 1; i++) {
                        fragmentToListMap.get(i).remove(object2);
                    }
                    if (object2 instanceof PubkeyIPObject) {
                        session
                                .createQuery(
                                        "delete PubkeyIPObject " +
                                                "where hostname = :hostname and port = :port")
                                .setParameter("hostname", ((PubkeyIPObject) object2).hostname)
                                .setParameter("port", ((PubkeyIPObject) object2).port)
                                .executeUpdate();
                    }
                    if (object2 instanceof PubkeyChannelObject) {
                        session
                                .createQuery(
                                        "delete PubkeyChannelObject " +
                                                "where (pubkeyA = :pubkeyA and pubkeyB = :pubkeyB) " +
                                                "or (pubkeyA = :pubkeyB and pubkeyB = :pubkeyA)")
                                .setParameter("pubkeyA", ((PubkeyChannelObject) object2).pubkeyA)
                                .setParameter("pubkeyB", ((PubkeyChannelObject) object2).pubkeyB)
                                .executeUpdate();
                    }
                    if (object2 instanceof ChannelStatusObject) {
                        session
                                .createQuery(
                                        "delete ChannelStatusObject " +
                                                "where pubkeyA = :pubkeyA and pubkeyB = :pubkeyB")
                                .setParameter("pubkeyA", ((ChannelStatusObject) object2).pubkeyA)
                                .setParameter("pubkeyB", ((ChannelStatusObject) object2).pubkeyB)
                                .executeUpdate();
                    }
                }
            }
            tx.commit();
            session.close();
        }

        for (P2PDataObject obj : dataList) {
            fragmentToListMap.get(obj.getFragmentIndex()).add(obj);
            if (obj instanceof PubkeyIPObject) {
                Session session = sessionFactory.openSession();
                Transaction tx = session.beginTransaction();
                Boolean found = session
                        .createQuery(
                                "from PubkeyIPObject " +
                                        "where hostname = :hostname " +
                                        "and port = :port",
                                PubkeyIPObjectEntity.class)
                        .setParameter("hostname", ((PubkeyIPObject) obj).hostname)
                        .setParameter("port", ((PubkeyIPObject) obj).port)
                        .list().stream().findFirst().isPresent();
                if (!found) {
                    session.save(new PubkeyIPObjectEntity((PubkeyIPObject) obj));
                }
                tx.commit();
                session.close();
            } else if (obj instanceof PubkeyChannelObject) {
                Session session = sessionFactory.openSession();
                Transaction tx = session.beginTransaction();
                Boolean found = session
                        .createQuery(
                                "from PubkeyChannelObject " +
                                        "where (pubkeyA = :pubkeyA and pubkeyB = :pubkeyB) " +
                                        "or (pubkeyA = :pubkeyB and pubkeyB = :pubkeyA)",
                                PubkeyChannelObjectEntity.class)
                        .setParameter("pubkeyA", ((PubkeyChannelObject) obj).pubkeyA)
                        .setParameter("pubkeyB", ((PubkeyChannelObject) obj).pubkeyB)
                        .list().stream().findFirst().isPresent();
                if (!found) {
                    session.save(new PubkeyChannelObjectEntity((PubkeyChannelObject) obj));
                }
                tx.commit();
                session.close();
            } else if (obj instanceof ChannelStatusObject) {
                ChannelStatusObject temp = (ChannelStatusObject) obj;
                Session session = sessionFactory.openSession();
                Transaction tx = session.beginTransaction();
                Boolean found = session
                        .createQuery(
                                "from ChannelStatusObject " +
                                        "where (pubkeyA = :pubkeyA and pubkeyB = :pubkeyB) " +
                                        "or (pubkeyA = :pubkeyB and pubkeyB = :pubkeyA)",
                                ChannelStatusObjectEntity.class)
                        .setParameter("pubkeyA", temp.pubkeyA)
                        .setParameter("pubkeyB", temp.pubkeyB)
                        .list().stream().findFirst().isPresent();
                if (!found) {
                    session.save(new ChannelStatusObjectEntity(temp));
                }
                tx.commit();
                session.close();
            }
            List<P2PDataObject> list = getSyncDataByFragmentIndex(obj.getFragmentIndex());
            if (!list.contains(obj)) {
                list.add(obj);
            }
            synchronized (totalList) {
                if (!totalList.contains(obj)) {
                    totalList.add(obj);
                }
            }
            knownObjects.add(ByteBuffer.wrap(obj.getHash()));
        }

    }

    @Override
    public Channel getChannel (int id) {
        Session session = sessionFactory.openSession();
        Transaction tx = session.beginTransaction();
        Optional<Channel> optional = session
                .byId(ChannelEntity.class)
                .loadOptional(id)
                .map(ChannelEntity::toChannel);
        tx.commit();
        session.close();
        if (optional.isPresent()) {
            return optional.get();
        } else {
            throw new RuntimeException("Channel not found..");
        }
    }

    @Override
    public Channel getChannel (Sha256Hash hash) {
        Session session = sessionFactory.openSession();
        Transaction tx = session.beginTransaction();
        Optional<Channel> channel = session
                .createQuery(
                        "from Channel c " +
                                "left join fetch c.signatures " +
                                "left join fetch c.paymentList " +
                                "where c.hash = :hash",
                        ChannelEntity.class)
                .setParameter("hash", hash)
                .list().stream().findFirst().map(ChannelEntity::toChannel);
        tx.commit();
        session.close();
        if (channel.isPresent()) {
            return channel.get();
        } else {
            throw new RuntimeException("Channel not found..");
        }
    }

    @Override
    public List<Channel> getChannel (NodeKey nodeKey) {
        Session session = sessionFactory.openSession();
        Transaction tx = session.beginTransaction();
        List<Channel> channels = session
                .createQuery(
                        "from Channel c " +
                                "left join fetch c.signatures " +
                                "left join fetch c.paymentList " +
                                "where c.nodeKeyClient = :nodeKey",
                        ChannelEntity.class)
                .setParameter("nodeKey", nodeKey)
                .list().stream()
                .map(ChannelEntity::toChannel)
                .collect(Collectors.toList());
        tx.commit();
        session.close();
        return channels;
    }

    @Override
    public List<Channel> getOpenChannel (NodeKey nodeKey) {
        Session session = sessionFactory.openSession();
        Transaction tx = session.beginTransaction();
        List<Channel> channels = session
                .createQuery(
                        "from Channel c " +
                                "left join fetch c.signatures " +
                                "left join fetch c.paymentList " +
                                "where c.nodeKeyClient = :nodeKey " +
                                "and c.phase = :phase",
                        ChannelEntity.class)
                .setParameter("nodeKey", nodeKey)
                .setParameter("phase", OPEN)
                .list().stream()
                .map(ChannelEntity::toChannel)
                .collect(Collectors.toList());
        tx.commit();
        session.close();
        return channels;
    }

    @Override
    public void insertChannel (Channel channel) {
        Session session = sessionFactory.openSession();
        Transaction tx = session.beginTransaction();
        ChannelEntity channelEntity = new ChannelEntity(channel);
        session.persist(channelEntity);
        tx.commit();
        session.close();
    }

    @Override
    public void updateChannelStatus (@NotNull NodeKey nodeKey, @NotNull Sha256Hash channelHash, @NotNull ECKey keyServer,
                                     Channel channel, ChannelUpdate update, List<RevocationHash> revocationHash,
                                     NumberedMessage request, NumberedMessage response) {

        if (request != null) {
            setMessageProcessed(nodeKey, request);
        }
        if (response != null) {
            long messageId = saveMessage(nodeKey, response, DIRECTION.SENT);
            response.setMessageNumber(messageId);
            if (request != null) {
                linkResponse(nodeKey, request.getMessageNumber(), response.getMessageNumber());
            }
        }
        if (revocationHash != null) {
            List<RevocationHash> revocationList = revocationHashListTheir.get(channelHash);
            if (revocationList == null) {
                revocationList = new ArrayList<>();
                revocationHashListTheir.put(channelHash, revocationList);
            }
            revocationList.addAll(revocationHash);
        }
        if (channel != null) {
            Session session = sessionFactory.openSession();
            Transaction tx = session.beginTransaction();
            List<ChannelEntity> channels = session
                    .createQuery(
                            "from Channel c " +
                                    "left join fetch c.signatures " +
                                    "left join fetch c.paymentList " +
                                    "where c.hash = :hash",
                            ChannelEntity.class)
                    .setParameter("hash", channel.getHash())
                    .list();
            int result = channels.size();
            channels.forEach(session::delete);
            if (result == 0) {
                tx.rollback();
                session.close();
                throw new RuntimeException("Not able to find channel in list, not updated..");
            } else {
                ChannelEntity channelEntity = new ChannelEntity(channel);
                session.persist(channelEntity);
                tx.commit();
                session.close();
            }
        }
        synchronized (payments) {
            if (update != null) {
                for (PaymentData payment : update.newPayments) {
                    PaymentWrapper paymentWrapper;
                    if (!payment.sending) {
                        paymentWrapper = new PaymentWrapper();
                        payments.add(paymentWrapper);
                        paymentWrapper.paymentData = payment;
                        paymentWrapper.sender = nodeKey;
                        paymentWrapper.statusSender = EMBEDDED;

                        PeeledOnion onion = null;
                        onion = onionHelper.loadMessage(keyServer, payment.onionObject);

                        if (secrets.contains(payment.secret)) {
                            paymentWrapper.paymentData.secret = getPaymentSecret(payment.secret);
                            paymentWrapper.statusSender = TO_BE_REDEEMED;
                        } else if (onion.isLastHop) {
                            System.out.println("Don't have the payment secret - refund..");
                            paymentWrapper.statusSender = TO_BE_REFUNDED;
                        } else {

                            NodeKey nextHop = onion.nextHop;
                            if (getOpenChannel(nextHop).size() > 0) {
                                paymentWrapper.statusReceiver = TO_BE_EMBEDDED;
                                paymentWrapper.receiver = nextHop;
                                payment.onionObject = onion.onionObject;
                            } else {
                                System.out.println("HibernateHandler.updateChannelStatus to be refunded?");
                                paymentWrapper.statusSender = TO_BE_REFUNDED;
                            }
                        }
                    } else {
                        paymentWrapper = getPayment(payment.secret);
                        paymentWrapper.statusReceiver = EMBEDDED;
                    }
                }

                for (PaymentData payment : update.redeemedPayments) {
                    addPaymentSecret(payment.secret);
                    PaymentWrapper paymentWrapper = getPayment(payment.secret);
                    if (paymentWrapper == null) {
                        throw new RuntimeException("Redeemed an unknown payment?");
                    }

                    paymentWrapper.paymentData.secret = payment.secret;
                    if (Objects.equals(paymentWrapper.receiver, nodeKey)) {
                        paymentWrapper.statusReceiver = REDEEMED;
                        paymentWrapper.statusSender = TO_BE_REDEEMED;
                    } else if (Objects.equals(paymentWrapper.sender, nodeKey)) {
                        paymentWrapper.statusSender = REDEEMED;
                    } else {
                        throw new RuntimeException("Neither of the parties involved in payment is the one who got here?");
                    }
                }

                for (PaymentData payment : update.refundedPayments) {
                    PaymentWrapper paymentWrapper = getPayment(payment.secret);
                    if (paymentWrapper == null) {
                        throw new RuntimeException("Refunded an unknown payment?");
                    }

                    if (Objects.equals(paymentWrapper.receiver, nodeKey)) {
                        paymentWrapper.statusReceiver = REFUNDED;
                        paymentWrapper.statusSender = TO_BE_REFUNDED;
                    } else if (Objects.equals(paymentWrapper.sender, nodeKey)) {
                        paymentWrapper.statusSender = REFUNDED;
                    } else {
                        throw new RuntimeException("Neither of the parties involved in payment is the one who got here?");
                    }
                }
                unlockPayments(nodeKey, payments.stream().map(p -> p.paymentData).collect(Collectors.toList()));
            }
        }
    }

    @Override
    public void checkPaymentsList () {
        synchronized (payments) {
            for (PaymentWrapper payment : payments) {
                if (payment.statusSender == EMBEDDED && payment.statusReceiver == TO_BE_EMBEDDED) {
                    if (Tools.currentTime() - payment.paymentData.timestampOpen > Constants.PAYMENT_TIMEOUT) {
                        payment.statusReceiver = UNKNOWN;
                        payment.statusSender = TO_BE_REFUNDED;
                    }
                }
            }
        }
    }

    @Override
    public List<PaymentData> lockPaymentsToBeRefunded (NodeKey nodeKey) {
        synchronized (payments) {
            return getPaymentDatas(nodeKey, payments, true, TO_BE_REFUNDED, CURRENTLY_REFUNDING);
        }
    }

    @Override
    public List<PaymentData> lockPaymentsToBeMade (NodeKey nodeKey) {
        synchronized (payments) {
            return getPaymentDatas(nodeKey, payments, false, TO_BE_EMBEDDED, CURRENTLY_EMBEDDING);
        }
    }

    @Override
    public List<PaymentData> lockPaymentsToBeRedeemed (NodeKey nodeKey) {
        synchronized (payments) {
            return getPaymentDatas(nodeKey, payments, true, TO_BE_REDEEMED, CURRENTLY_REDEEMING);
        }
    }

    @NotNull
    private static List<PaymentData> getPaymentDatas (
            NodeKey nodeKey,
            List<PaymentWrapper> payments,
            boolean sender,
            PaymentStatus searchFor,
            PaymentStatus
            replaceWith) {
        List<PaymentData> paymentList = new ArrayList<>();
        for (PaymentWrapper p : payments) {
            if (sender) {
                if (nodeKey.equals(p.sender)) {
                    if (p.statusSender == searchFor) {
                        p.statusSender = replaceWith;
                        paymentList.add(p.paymentData);
                    }
                }
            } else {
                if (nodeKey.equals(p.receiver)) {
                    if (p.statusReceiver == searchFor) {
                        p.statusReceiver = replaceWith;
                        paymentList.add(p.paymentData);
                    }
                }
            }
        }
        return paymentList;
    }

    @Override
    public void unlockPayments (NodeKey nodeKey, List<PaymentData> paymentList) {
        synchronized (payments) {

            payments.stream()
                    .filter(p -> Objects.equals(p.receiver, nodeKey))
                    .filter(p -> paymentList.contains(p.paymentData))
                    .forEach(p -> {
                        if (p.statusReceiver == CURRENTLY_EMBEDDING) {
                            p.statusReceiver = TO_BE_EMBEDDED;
                        }
                        if (p.statusReceiver == CURRENTLY_REDEEMING) {
                            p.statusReceiver = TO_BE_REDEEMED;
                        }
                        if (p.statusReceiver == CURRENTLY_REFUNDING) {
                            p.statusReceiver = TO_BE_REFUNDED;
                        }
                    });

            payments.stream()
                    .filter(p -> Objects.equals(p.sender, nodeKey))
                    .filter(p -> paymentList.contains(p.paymentData))
                    .forEach(p -> {
                        if (p.statusSender == CURRENTLY_EMBEDDING) {
                            p.statusSender = TO_BE_EMBEDDED;
                        }
                        if (p.statusSender == CURRENTLY_REDEEMING) {
                            p.statusSender = TO_BE_REDEEMED;
                        }
                        if (p.statusSender == CURRENTLY_REFUNDING) {
                            p.statusSender = TO_BE_REFUNDED;
                        }
                    });
        }
    }

    @Override
    public List<Channel> getOpenChannel () {
        Session session = sessionFactory.openSession();
        Transaction tx = session.beginTransaction();
        List<Channel> channels = session
                .createQuery(
                        "from Channel c " +
                                "left join fetch c.signatures " +
                                "left join fetch c.paymentList " +
                                "where c.phase = :phase",
                        ChannelEntity.class)
                .setParameter("phase", OPEN)
                .list().stream()
                .map(ChannelEntity::toChannel)
                .collect(Collectors.toList());
        tx.commit();
        session.close();
        return channels;
    }

    @Override
    public List<MessageWrapper> getMessageList (NodeKey nodeKey, Sha256Hash channelHash, Class c) {
        List<MessageWrapper> wrapper = messageList.get(nodeKey);
        if (wrapper == null) {
            return Collections.emptyList();
        }
        List<MessageWrapper> total =
                wrapper.stream()
                        .filter(message -> c.isAssignableFrom(message.getMessage().getClass()))
                        .collect(Collectors.toList());

        return total;
    }

    @Override
    public void setMessageProcessed (NodeKey nodeKey, NumberedMessage message) {
        saveMessage(nodeKey, message, DIRECTION.RECEIVED);
    }

    @Override
    public long lastProcessedMessaged (NodeKey nodeKey) {
        List<MessageWrapper> messageWrappers = messageList.get(nodeKey);
        if (messageWrappers != null) {
            Optional<Long> o = messageWrappers.stream()
                    .filter(w -> w.getDirection() == DIRECTION.RECEIVED)
                    .filter(w -> w.getMessage() instanceof NumberedMessage)
                    .map(MessageWrapper::getMessage)
                    .map(m -> (NumberedMessage) m)
                    .map(NumberedMessage::getMessageNumber)
                    .max(Long::compareTo);

            if (o.isPresent()) {
                return o.get();
            }
        }
        return 0;
    }

    @Override
    public synchronized long saveMessage (NodeKey nodeKey, NumberedMessage message, DIRECTION direction) {
        if (direction == DIRECTION.SENT) {
            Long i = messageCountList.get(nodeKey);
            if (i == null) {
                i = 1L;
            } else {
                i++;
            }
            messageCountList.put(nodeKey, i);
            Map<Long, NumberedMessage> messageMap = sentMessages.get(nodeKey);
            if (messageMap == null) {
                messageMap = new ConcurrentHashMap<>();
                sentMessages.put(nodeKey, messageMap);
            }
            message.setMessageNumber(i);
            messageMap.put(i, message);

            if (message instanceof AckableMessage) {
                List<AckableMessage> unAckedMessageList = unAckedMessageMap.get(nodeKey);
                if (unAckedMessageList == null) {
                    unAckedMessageList = new ArrayList<>();
                    unAckedMessageMap.put(nodeKey, unAckedMessageList);
                }
                unAckedMessageList.add((AckableMessage) message);
            }

            List<MessageWrapper> messageList = this.messageList.get(nodeKey);
            if (messageList == null) {
                messageList = new ArrayList<>();
                this.messageList.put(nodeKey, messageList);
            }
            messageList.add(new MessageWrapper(message, Tools.currentTime(), direction));
            return i;
        } else {
            List<MessageWrapper> messageList = this.messageList.get(nodeKey);
            if (messageList == null) {
                messageList = new ArrayList<>();
                this.messageList.put(nodeKey, messageList);
            }
            messageList.add(new MessageWrapper(message, Tools.currentTime(), direction));
            return 0;
        }
    }

    @Override
    public void linkResponse (NodeKey nodeKey, long messageRequest, long messageResponse) {
        Map<Long, Long> linkedMessages = linkedMessageMap.get(nodeKey);
        if (linkedMessages == null) {
            linkedMessages = new ConcurrentHashMap<>();
            linkedMessageMap.put(nodeKey, linkedMessages);
        }
        linkedMessages.put(messageRequest, messageResponse);
    }

    @Override
    public List<AckableMessage> getUnackedMessageList (NodeKey nodeKey) {
        List<AckableMessage> unAckedMessageList = unAckedMessageMap.get(nodeKey);
        if (unAckedMessageList == null || unAckedMessageList.size() == 0) {
            return Collections.emptyList();
        } else {
            return unAckedMessageList;
        }
    }

    @Override
    public NumberedMessage getMessageResponse (NodeKey nodeKey, long messageIdReceived) {
        Map<Long, Long> linkMap = linkedMessageMap.get(nodeKey);
        if (linkMap != null) {
            Long response = linkMap.get(messageIdReceived);
            if (response != null) {
                Map<Long, NumberedMessage> messageMap = sentMessages.get(nodeKey);
                if (messageMap != null) {
                    NumberedMessage m = messageMap.get(response);
                    if (m != null) {
                        return m;
                    }
                }
                throw new RuntimeException("We wanted to resend an old message, but can't find it..");
            }
        }
        return null;
    }

    @Override
    public void setMessageAcked (NodeKey nodeKey, long messageId) {
        List<AckableMessage> unackedMessages = unAckedMessageMap.get(nodeKey);
        unackedMessages.removeIf(message -> message.getMessageNumber() == messageId);
    }

    @Override
    public List<P2PDataObject> getSyncDataByFragmentIndex (int fragmentIndex) {
//        cleanFragmentMap();
        fragmentToListMap.get(fragmentIndex).removeIf(
                p -> (Tools.currentTime() - p.getTimestamp() > P2PDataObject.MAXIMUM_AGE_SYNC_DATA));
        return fragmentToListMap.get(fragmentIndex);
    }

    @Override
    public List<P2PDataObject> getSyncDataIPObjects () {
        return null;
    }

    @Override
    public List<PubkeyIPObject> getIPObjectsWithActiveChannel () {
        return new ArrayList<>();
    }

    @Override
    public List<ChannelStatusObject> getTopology () {
        List<ChannelStatusObject> list = new ArrayList<>();
        synchronized (totalList) {
            for (P2PDataObject object : totalList) {
                if (object instanceof ChannelStatusObject) {
                    list.add((ChannelStatusObject) object);
                }
            }
        }
        return list;
    }

    @Override
    public List<PaymentWrapper> getAllPayments () {
        return new ArrayList<>(payments);
    }

    @Override
    public List<PaymentWrapper> getOpenPayments () {
        return new ArrayList<>();
    }

    @Override
    public List<PaymentWrapper> getRefundedPayments () {
        return new ArrayList<>();
    }

    @Override
    public List<PaymentWrapper> getRedeemedPayments () {
        return new ArrayList<>();
    }

    @Override
    public void addPayment (NodeKey nodeKey, PaymentData paymentData) {
        PaymentWrapper paymentWrapper = new PaymentWrapper();
        paymentWrapper.receiver = nodeKey;
        paymentWrapper.statusReceiver = TO_BE_EMBEDDED;
        paymentWrapper.paymentData = paymentData;
        synchronized (payments) {
            payments.add(paymentWrapper);
        }
    }

    @Override
    public void updatePayment (PaymentWrapper paymentWrapper) {
        synchronized (payments) {
            for (PaymentWrapper p : payments) {
                if (p.equals(paymentWrapper)) {
                    p.paymentData = paymentWrapper.paymentData;
                    p.receiver = paymentWrapper.receiver;
                    p.sender = paymentWrapper.sender;
                    p.statusReceiver = paymentWrapper.statusReceiver;
                    p.statusSender = paymentWrapper.statusSender;
                    return;
                }
            }
            payments.add(paymentWrapper);
        }
    }

    @Override
    public PaymentWrapper getPayment (PaymentSecret paymentSecret) {
        synchronized (payments) {
            Optional<PaymentWrapper> paymentWrapper = payments.stream()
                    .filter(p -> !isPaymentComplete(p))
                    .filter(p -> Objects.equals(p.paymentData.secret, paymentSecret))
                    .findAny();

            if (paymentWrapper.isPresent()) {
                return paymentWrapper.get();
            } else {
                return null;
            }
        }
    }

    private boolean isPaymentComplete (PaymentWrapper paymentWrapper) {
        return paymentWrapper.statusSender == REDEEMED && paymentWrapper.statusReceiver == REDEEMED ||
                paymentWrapper.statusSender == REFUNDED && paymentWrapper.statusReceiver == REFUNDED;
    }

    @Override
    public void addPaymentSecret (PaymentSecret secret) {
        if (secrets.contains(secret)) {
            PaymentSecret oldSecret = secrets.get(secrets.indexOf(secret));
            oldSecret.secret = secret.secret;
        } else {
            secrets.add(secret);
        }
    }

    @Override
    public PaymentSecret getPaymentSecret (PaymentSecret secret) {
        if (!secrets.contains(secret)) {
            return null;
        }
        return secrets.get(secrets.indexOf(secret));
    }

    @Override
    public NodeKey getSenderOfPayment (PaymentSecret paymentSecret) {
        synchronized (payments) {
            for (PaymentWrapper payment : payments) {
                if (payment.paymentData.secret.equals(paymentSecret)) {
                    return payment.sender;
                }
            }
            return null;
        }
    }
}
