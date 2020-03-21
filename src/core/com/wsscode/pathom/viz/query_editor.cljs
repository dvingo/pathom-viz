(ns com.wsscode.pathom.viz.query-editor
  (:require [cljs.reader :refer [read-string]]
            [com.wsscode.async.async-cljs :refer [<?maybe go-promise <!]]
            [com.wsscode.pathom.connect :as pc]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.fulcro.network :as pfn]
            [com.wsscode.pathom.viz.codemirror :as cm]
            [com.wsscode.pathom.viz.helpers :as pvh]
            [com.wsscode.pathom.viz.trace :as pvt]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro-css.localized-dom :as dom]
            [com.fulcrologic.fulcro.mutations :as fm]
            [com.fulcrologic.fulcro.components :as fc]
            [com.fulcrologic.fulcro.application :as fa]
            [com.fulcrologic.fulcro-css.css :as css]))

(declare QueryEditor TransactionResponse)

(def remote-key :pathom-query-editor-remote)

(defn safe-read [s]
  (try
    (read-string s)
    (catch :default _ nil)))

;; Parser

(pc/defresolver indexes [{::keys [client-parser]} _]
  {::pc/output [::pc/indexes]}
  (client-parser {} [{::pc/indexes [::pc/idents ::pc/index-io ::pc/autocomplete-ignore]}]))

(pc/defmutation run-query-server [{::keys [client-parser]} {::keys [id query request-trace?]}]
  {::pc/sym    `run-query
   ::pc/params [::id ::query ::request-trace?]
   ::pc/output [::id ::result]}
  (go-promise
    (let [pull-keys [:com.wsscode.pathom/trace]
          query     (cond-> (safe-read query) request-trace? (conj :com.wsscode.pathom/trace))
          response  (<?maybe (client-parser {} query))]
      (merge
        {::id                      id
         ::result                  (pvh/pprint (apply dissoc response pull-keys))
         :com.wsscode.pathom/trace nil}
        (select-keys response pull-keys)))))

(defn client-card-parser
  "Returns a new parser that will use the card-parser setting the client
  parser to be `client-parser`."
  ([client-parser] (client-card-parser client-parser {}))
  ([client-parser {::keys [wrap-run-query]}]
   (let [card-parser
         (p/async-parser {::p/env     {::p/reader [p/map-reader pc/async-reader2 pc/open-ident-reader]}
                          ::p/mutate  pc/mutate-async
                          ::p/plugins [p/error-handler-plugin
                                       p/request-cache-plugin
                                       (-> (pc/connect-plugin {::pc/register [indexes (cond-> run-query-server
                                                                                        wrap-run-query
                                                                                        (update ::pc/mutate wrap-run-query))]})
                                           (dissoc ::pc/register))
                                       p/trace-plugin]})]
     (fn [env tx]
       (card-parser (assoc env ::client-parser client-parser) tx)))))

(fm/defmutation run-query [_]
  (action [_] nil)
  (remote [env]
    (fm/returning env TransactionResponse)))

(defn load-indexes
  [app-or-reconciler]
  (let [app        (or (:app app-or-reconciler) app-or-reconciler)
        root-ident (-> app fa/current-state :ui/root)]
    (df/load app root-ident QueryEditor
      {:focus  [::pc/indexes]
       :remote remote-key})))

;; UI

(fc/defsc TransactionResponse [_ _]
  {:ident [::id ::id]
   :query [::id ::result :com.wsscode.pathom/trace]})

(fc/defsc Button
  [this props]
  {:css [[:.container
          {:font-size   "11px"
           :font-family "'Open Sans', sans-serif"
           :font-weight "600"}
          {:background-color "#4b5b6d"
           :border           "none"
           :border-radius    "3px"
           :color            "#fff"
           :cursor           "pointer"
           :display          "inline-block"
           :padding          "2px 8px"
           :line-height      "1.5"
           :margin-bottom    "0"
           :text-align       "center"
           :white-space      "nowrap"
           :vertical-align   "middle"
           :user-select      "none"
           :outline          "none"}
          [:&:disabled {:background "#b0c1d6"
                        :color      "#eaeaea"}]]]}
  (dom/button :.container props (fc/children this)))

(def button (fc/factory Button))

(fc/defsc QueryEditor
  [this
   {::keys                   [query result request-trace?]
    :ui/keys                 [query-running?]
    :com.wsscode.pathom/keys [trace]
    ::pc/keys                [indexes]}
   {::keys [default-trace-size editor-props
            enable-trace?]
    :or    {default-trace-size 400
            enable-trace?      true}}]
  {:initial-state     (fn [_]
                        {::id             (random-uuid)
                         ::request-trace? true
                         ::query          "[]"
                         ::result         ""})
   :pre-merge         (fn [{:keys [current-normalized data-tree]}]
                        (merge {::id             (random-uuid)
                                ::request-trace? true
                                ::query          "[]"
                                ::result         ""}
                          current-normalized data-tree))

   :ident             [::id ::id]
   :query             [::id ::request-trace? ::query ::result :ui/query-running?
                       ::pc/indexes :com.wsscode.pathom/trace]
   :css               [[:$CodeMirror {:height   "100% !important"
                                      :width    "100% !important"
                                      :position "absolute !important"
                                      :z-index  "1"}
                        [:$cm-atom-composite {:color "#ab890d"}]
                        [:$cm-atom-ident {:color       "#219"
                                          :font-weight "bold"}]]
                       [:$CodeMirror-hint {:font-size "10px"}]
                       [:.container {:border         "1px solid #ddd"
                                     :display        "flex"
                                     :flex-direction "column"
                                     :flex           "1"
                                     :max-width      "100%"
                                     :min-height     "200px"}]
                       [:.query-row {:display  "flex"
                                     :flex     "1"
                                     :position "relative"}]
                       [:.toolbar {:background    "#eeeeee"
                                   :border-bottom "1px solid #e0e0e0"
                                   :padding       "5px 4px"
                                   :display       "flex"
                                   :align-items   "center"
                                   :font-family   "sans-serif"
                                   :font-size     "13px"}
                        [:label {:display     "flex"
                                 :align-items "center"}
                         [:input {:margin-right "5px"}]]]
                       [:.flex {:flex "1"}]
                       [:.editor {:position "relative"}]
                       [:.divisor-v {:width         "20px"
                                     :background    "#eee"
                                     :border        "1px solid #e0e0e0"
                                     :border-top    "0"
                                     :border-bottom "0"
                                     :z-index       "2"}]
                       [:.divisor-h {:height       "20px"
                                     :background   "#eee"
                                     :border       "1px solid #e0e0e0"
                                     :border-left  "0"
                                     :border-right "0"
                                     :z-index      "2"}]
                       [:.result {:flex     "1"
                                  :position "relative"}
                        [:$CodeMirror {:background "#f6f7f8"}]]
                       [:.trace {:display     "flex"
                                 :padding-top "18px"}]]
   :css-include       [pvt/D3Trace Button]
   :componentDidMount (fn [this]
                        (js/setTimeout
                          #(fc/set-state! this {:render? true})
                          100))
   :initLocalState    (fn [this]
                        {:run-query (fn []
                                      (let [{:ui/keys [query-running?] :as props} (fc/props this)
                                            {::keys [enable-trace?]} (fc/get-computed props)]
                                        (if-not query-running?
                                          (let [props (update props ::request-trace? #(and enable-trace? %))]
                                            (fc/ptransact! this [`(fm/set-props {:ui/query-running? true})
                                                                 `(run-query ~props)
                                                                 `(fm/set-props {:ui/query-running? false})])))))})}
  (let [run-query (fc/get-state this :run-query)
        css (css/get-classnames QueryEditor)]
    (dom/div :.container
      (dom/div :.toolbar
        (if enable-trace?
          (dom/label
            (dom/input {:type     "checkbox"
                        :checked  request-trace?
                        :onChange #(fm/toggle! this ::request-trace?)})
            "Request trace"))
        (dom/div :.flex)
        (button {:onClick #(load-indexes (fc/any->app this))
                 :style   {:marginRight "6px"}}
          "Refresh index")
        (button {:onClick  run-query
                 :disabled query-running?}
          "Run query"))

      (dom/div :.query-row
        (when (fc/get-state this :render?)
          (cm/pathom
            (merge {:className   (:editor css)
                    :style       {:width (str (or (fc/get-state this :query-width) 400) "px")}
                    :value       (or (str query) "")
                    ::pc/indexes (if (map? indexes) (p/elide-not-found indexes))
                    ::cm/options {::cm/extraKeys
                                  {"Cmd-Enter"   run-query
                                   "Ctrl-Enter"  run-query
                                   "Shift-Enter" run-query
                                   "Cmd-J"       "pathomJoin"
                                   "Ctrl-Space"  "autocomplete"}}
                    :onChange    #(fm/set-value! this ::query %)}
              editor-props)))
        (pvh/drag-resize this {:attribute :query-width
                               :axis      "x"
                               :default   400
                               :props     {:className (:divisor-v css)}}
          (dom/div))
        (if (fc/get-state this :render?)
          (cm/clojure
            (merge {:className   (:result css)
                    :value       result
                    ::cm/options {::cm/readOnly    true
                                  ::cm/lineNumbers true}}
              editor-props))))
      (if trace
        (pvh/drag-resize this {:attribute :trace-height
                               :default   default-trace-size
                               :props     {:className (:divisor-h css)}}
          (dom/div)))
      (if trace
        (dom/div :.trace {:style {:height (str (or (fc/get-state this :trace-height) default-trace-size) "px")}}
          (pvt/d3-trace {::pvt/trace-data      trace
                         ::pvt/on-show-details #(js/console.log %)}))))))

(def query-editor (fc/computed-factory QueryEditor))
